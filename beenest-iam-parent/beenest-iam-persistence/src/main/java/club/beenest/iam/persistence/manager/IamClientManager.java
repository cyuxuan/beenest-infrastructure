package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.persistence.converter.ClientConverter;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.ClientReferenceCount;
import club.beenest.iam.persistence.mapper.ClientMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 客户端存储(iam_client)。
 * <p>机密客户端密钥以 BCrypt 密文落库,明文只在创建与轮换时返回一次,之后无法读出。</p>
 */
@Component
public class IamClientManager implements ClientProvider {

    /** 客户端类型:机密 */
    private static final String TYPE_CONFIDENTIAL = "CONFIDENTIAL";
    /** 密钥随机源 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 客户端密钥字节数(编码后约 43 字符) */
    private static final int SECRET_BYTES = 32;

    private final ClientMapper clientMapper;
    private final ClientConverter clientConverter;
    private final PasswordEncoder passwordEncoder;

    public IamClientManager(ClientMapper clientMapper, ClientConverter clientConverter,
                            PasswordEncoder passwordEncoder) {
        this.clientMapper = clientMapper;
        this.clientConverter = clientConverter;
        this.passwordEncoder = passwordEncoder;
    }

    // ===== 认证授权侧:领域模型读取 =====

    @Override
    public Optional<ClientModel> findByClientKey(Long realmId, String clientKey) {
        return Optional.ofNullable(clientConverter.toModel(
                clientMapper.selectByRealmIdAndClientKey(realmId, clientKey)));
    }

    @Override
    public Optional<ClientModel> findById(Long id) {
        return Optional.ofNullable(clientConverter.toModel(clientMapper.selectById(id)));
    }

    // ===== 管理侧:单表 CRUD =====

    /**
     * 按主键查询实体。
     *
     * @param id 客户端主键
     * @return 客户端;不存在返回 null
     */
    public Client getById(Long id) {
        return clientMapper.selectById(id);
    }

    /**
     * 按域查询全部客户端。
     * <p>门户渲染卡片时一次取回,避免逐卡片查库。</p>
     *
     * @param realmId 身份域主键
     * @return 客户端列表
     */
    public List<Client> listByRealmId(Long realmId) {
        return clientMapper.selectByRealmId(realmId);
    }

    /**
     * 按域与客户端标识查询实体(唯一性校验)。
     *
     * @param realmId 身份域主键
     * @param clientKey 客户端标识
     * @return 客户端;不存在返回 null
     */
    public Client getByClientKey(Long realmId, String clientKey) {
        return clientMapper.selectByRealmIdAndClientKey(realmId, clientKey);
    }

    /**
     * 分页查询域内客户端。
     *
     * @param realmId 身份域主键
     * @param condition 查询条件,非空字段参与过滤
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 客户端分页结果
     */
    public PageResult<Client> page(Long realmId, Client condition, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Client> list = clientMapper.selectByCondition(realmId, condition);
        PageInfo<Client> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 新建客户端。
     *
     * @param client 客户端实体(需已填 realmId/clientKey/clientType)
     * @return 机密客户端的明文密钥;公共客户端返回 null
     */
    public String createWithSecret(Client client) {
        // 1. 公共客户端无密钥;机密客户端现场生成密钥,只存密文
        String plainSecret = null;
        if (TYPE_CONFIDENTIAL.equalsIgnoreCase(client.getClientType())) {
            plainSecret = generateSecret();
            client.setClientSecret(passwordEncoder.encode(plainSecret));
        } else {
            client.setClientSecret(null);
        }
        // 2. 落库并回填主键
        clientMapper.insert(client);
        return plainSecret;
    }

    /**
     * 更新客户端非空字段(不涉及密钥)。
     *
     * @param client 客户端实体,需含主键
     * @return 受影响行数
     */
    public int update(Client client) {
        return clientMapper.updateById(client);
    }

    /**
     * 轮换机密客户端密钥。
     *
     * @param id 客户端主键
     * @return 新的明文密钥(仅本次返回);非机密客户端或客户端不存在时返回 null
     */
    public String rotateSecret(Long id) {
        // 1. 客户端必须存在且为机密类型
        Client client = clientMapper.selectById(id);
        if (client == null || !TYPE_CONFIDENTIAL.equalsIgnoreCase(client.getClientType())) {
            return null;
        }
        // 2. 生成新密钥,只落密文
        String plainSecret = generateSecret();
        Client update = new Client();
        update.setId(id);
        update.setClientSecret(passwordEncoder.encode(plainSecret));
        clientMapper.updateById(update);
        return plainSecret;
    }

    /**
     * 逻辑删除客户端。
     *
     * @param id 客户端主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return clientMapper.softDeleteById(id);
    }

    /**
     * 变更客户端类型,并按目标类型重置密钥与 PKCE 要求。
     * <p>机密客户端现场生成密钥且只存密文,公共客户端清空密钥。调用方负责判定
     * "该客户端是否允许改类型"(是否已投入使用、是否与凭据模式冲突)。</p>
     *
     * @param id          客户端主键
     * @param clientType  目标类型 PUBLIC / CONFIDENTIAL
     * @param requirePkce 目标 PKCE 要求
     * @return 机密客户端的明文密钥;公共客户端返回 null
     */
    public String changeType(Long id, String clientType, Boolean requirePkce) {
        // 1. 目标为机密类型时现场生成密钥,只落密文
        String plainSecret = null;
        String secretCipher = null;
        if (TYPE_CONFIDENTIAL.equalsIgnoreCase(clientType)) {
            plainSecret = generateSecret();
            secretCipher = passwordEncoder.encode(plainSecret);
        }
        // 2. 三列一次性写入:类型、密钥(可空)、PKCE
        clientMapper.updateTypeById(id, clientType, secretCipher, requirePkce);
        return plainSecret;
    }

    /**
     * 统计指向该客户端的下游引用,按类别给出明细。
     *
     * @param clientId 客户端主键
     * @return 分类引用计数;客户端不存在时各项为 0
     */
    public ClientReferenceCount countReferences(Long clientId) {
        return clientMapper.countReferences(clientId);
    }

    /**
     * 生成 URL 安全的随机密钥。
     *
     * @return 明文密钥
     */
    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
