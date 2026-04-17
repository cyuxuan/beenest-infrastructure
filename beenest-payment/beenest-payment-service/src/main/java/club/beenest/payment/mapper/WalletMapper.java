package club.beenest.payment.mapper;

import club.beenest.payment.object.entity.Wallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 钱包数据访问接口
 * 提供钱包相关的数据库操作方法
 * 
 * <p>主要功能：</p>
 * <ul>
 *   <li>钱包基础CRUD操作</li>
 *   <li>余额查询和更新</li>
 *   <li>钱包状态管理</li>
 *   <li>并发安全的余额操作</li>
 * </ul>
 * 
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>乐观锁控制 - 使用版本号防止并发冲突</li>
 *   <li>原子性操作 - 确保余额更新的原子性</li>
 *   <li>数据一致性 - 保证钱包数据的一致性</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Mapper
public interface WalletMapper {

    /**
     * 根据主键ID查询钱包信息
     * 
     * <p>通过钱包的主键ID查询完整的钱包信息。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>内部系统查询</li>
     *   <li>数据关联查询</li>
     *   <li>管理后台操作</li>
     * </ul>
     * 
     * @param id 钱包主键ID，不能为null
     * @return 钱包实体对象，如果不存在则返回null
     */
    Wallet selectById(@Param("id") Long id);

    /**
     * 根据钱包编号查询钱包信息
     * 
     * <p>通过钱包编号查询完整的钱包信息，这是最常用的查询方式。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>业务逻辑查询</li>
     *   <li>API接口调用</li>
     *   <li>余额查询操作</li>
     * </ul>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>基于唯一索引，查询效率高</li>
     *   <li>返回完整的钱包信息</li>
     *   <li>支持状态过滤</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null或空字符串
     * @return 钱包实体对象，如果不存在则返回null
     */
    Wallet selectByWalletNo(@Param("walletNo") String walletNo);

    /**
     * 根据用户编号查询钱包信息
     * 
     * <p>通过用户编号查询该用户的钱包信息。每个用户只有一个钱包。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>用户登录后查询钱包</li>
     *   <li>订单支付时查询余额</li>
     *   <li>用户中心显示余额</li>
     * </ul>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>一个用户只能有一个钱包</li>
     *   <li>如果用户没有钱包，需要先创建</li>
     *   <li>只返回状态正常的钱包</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为null或空字符串
     * @return 钱包实体对象，如果不存在则返回null
     */
    Wallet selectByCustomerNo(@Param("customerNo") String customerNo);

    /**
     * 根据用户编号和业务类型查询钱包（多租户）
     *
     * @param customerNo 用户编号
     * @param bizType 业务类型
     * @return 钱包实体
     */
    Wallet selectByCustomerNoAndBizType(@Param("customerNo") String customerNo,
                                         @Param("bizType") String bizType);

    /**
     * 查询钱包列表（管理端使用）
     *
     * @param customerNo 用户编号，可为空
     * @param walletNo 钱包编号，可为空
     * @param status 状态，可为空
     * @return 钱包列表
     */
    List<Wallet> selectAllWithConditions(@Param("customerNo") String customerNo,
                                         @Param("walletNo") String walletNo,
                                         @Param("status") String status,
                                         @Param("bizType") String bizType);

    /**
     * 插入新的钱包记录
     * 
     * <p>创建新的用户钱包，设置初始余额和状态。</p>
     * 
     * <h4>创建规则：</h4>
     * <ul>
     *   <li>钱包编号必须唯一</li>
     *   <li>用户编号必须存在且唯一</li>
     *   <li>初始余额为0</li>
     *   <li>初始状态为ACTIVE</li>
     *   <li>版本号从0开始</li>
     * </ul>
     * 
     * <h4>字段设置：</h4>
     * <ul>
     *   <li>balance: 0（初始余额）</li>
     *   <li>frozenBalance: 0（初始冻结余额）</li>
     *   <li>status: ACTIVE（激活状态）</li>
     *   <li>version: 0（初始版本）</li>
     *   <li>createTime: 当前时间</li>
     *   <li>updateTime: 当前时间</li>
     * </ul>
     * 
     * @param wallet 钱包实体对象，必须包含walletNo和customerNo
     * @return 影响的行数，成功插入返回1
     */
    int insert(Wallet wallet);

    /**
     * 更新钱包信息（基础更新）
     * 
     * <p>更新钱包的基本信息，不包括余额字段。</p>
     * 
     * <h4>可更新字段：</h4>
     * <ul>
     *   <li>status - 钱包状态</li>
     *   <li>remark - 备注信息</li>
     *   <li>updateTime - 更新时间（自动设置）</li>
     * </ul>
     * 
     * <h4>不可更新字段：</h4>
     * <ul>
     *   <li>balance - 余额（需要使用专门的方法）</li>
     *   <li>frozenBalance - 冻结余额</li>
     *   <li>totalRecharge - 累计充值</li>
     *   <li>totalWithdraw - 累计提现</li>
     *   <li>totalConsume - 累计消费</li>
     * </ul>
     * 
     * @param wallet 钱包实体对象，必须包含id
     * @return 影响的行数，成功更新返回1
     */
    int updateById(Wallet wallet);

    /**
     * 增加钱包余额（原子操作）
     * 
     * <p>原子性地增加钱包余额，同时更新相关统计字段。</p>
     * 
     * <h4>操作特性：</h4>
     * <ul>
     *   <li>原子性操作，确保数据一致性</li>
     *   <li>使用乐观锁防止并发冲突</li>
     *   <li>自动更新版本号</li>
     *   <li>自动更新时间戳</li>
     * </ul>
     * 
     * <h4>更新字段：</h4>
     * <ul>
     *   <li>balance = balance + amount</li>
     *   <li>totalRecharge = totalRecharge + amount（如果是充值）</li>
     *   <li>version = version + 1</li>
     *   <li>updateTime = 当前时间</li>
     * </ul>
     * 
     * <h4>并发控制：</h4>
     * <ul>
     *   <li>基于版本号的乐观锁</li>
     *   <li>如果版本号不匹配，更新失败</li>
     *   <li>调用方需要重试机制</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @param amount 增加的金额（分），必须大于0
     * @param version 当前版本号，用于乐观锁控制
     * @param isRecharge 是否为充值操作，true表示同时更新totalRecharge
     * @return 影响的行数，成功返回1，版本冲突返回0
     */
    int addBalance(@Param("walletNo") String walletNo,
                   @Param("amount") Long amount,
                   @Param("version") Integer version,
                   @Param("isRecharge") Boolean isRecharge,
                   @Param("balanceHash") String balanceHash);

    /**
     * 扣减钱包余额（原子操作）
     * 
     * <p>原子性地扣减钱包余额，同时检查余额充足性。</p>
     * 
     * <h4>操作特性：</h4>
     * <ul>
     *   <li>原子性操作，确保数据一致性</li>
     *   <li>余额充足性检查</li>
     *   <li>使用乐观锁防止并发冲突</li>
     *   <li>自动更新版本号</li>
     * </ul>
     * 
     * <h4>更新字段：</h4>
     * <ul>
     *   <li>balance = balance - amount</li>
     *   <li>totalConsume = totalConsume + amount（如果是消费）</li>
     *   <li>totalWithdraw = totalWithdraw + amount（如果是提现）</li>
     *   <li>version = version + 1</li>
     *   <li>updateTime = 当前时间</li>
     * </ul>
     * 
     * <h4>安全检查：</h4>
     * <ul>
     *   <li>余额必须大于等于扣减金额</li>
     *   <li>钱包状态必须为ACTIVE</li>
     *   <li>版本号必须匹配</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @param amount 扣减的金额（分），必须大于0
     * @param version 当前版本号，用于乐观锁控制
     * @param isWithdraw 是否为提现操作，true表示同时更新totalWithdraw
     * @param isConsume 是否为消费操作，true表示同时更新totalConsume
     * @return 影响的行数，成功返回1，余额不足或版本冲突返回0
     */
    int deductBalance(@Param("walletNo") String walletNo,
                      @Param("amount") Long amount,
                      @Param("version") Integer version,
                      @Param("isWithdraw") Boolean isWithdraw,
                      @Param("isConsume") Boolean isConsume,
                      @Param("balanceHash") String balanceHash);

    /**
     * 冻结钱包余额
     * 
     * <p>将指定金额从可用余额转移到冻结余额。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>订单支付前预扣款</li>
     *   <li>提现申请时冻结资金</li>
     *   <li>风控措施临时冻结</li>
     * </ul>
     * 
     * <h4>操作逻辑：</h4>
     * <ul>
     *   <li>balance = balance - amount</li>
     *   <li>frozenBalance = frozenBalance + amount</li>
     *   <li>总余额不变</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @param amount 冻结的金额（分），必须大于0
     * @param version 当前版本号，用于乐观锁控制
     * @return 影响的行数，成功返回1，余额不足或版本冲突返回0
     */
    int freezeBalance(@Param("walletNo") String walletNo,
                      @Param("amount") Long amount,
                      @Param("version") Integer version,
                      @Param("balanceHash") String balanceHash);

    /**
     * 解冻钱包余额
     * 
     * <p>将指定金额从冻结余额转移回可用余额。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>订单取消后释放资金</li>
     *   <li>提现失败后解冻资金</li>
     *   <li>风控解除后恢复资金</li>
     * </ul>
     * 
     * <h4>操作逻辑：</h4>
     * <ul>
     *   <li>frozenBalance = frozenBalance - amount</li>
     *   <li>balance = balance + amount</li>
     *   <li>总余额不变</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @param amount 解冻的金额（分），必须大于0
     * @param version 当前版本号，用于乐观锁控制
     * @return 影响的行数，成功返回1，冻结余额不足或版本冲突返回0
     */
    int unfreezeBalance(@Param("walletNo") String walletNo,
                        @Param("amount") Long amount,
                        @Param("version") Integer version,
                        @Param("balanceHash") String balanceHash);

    /**
     * 扣减冻结余额
     * 
     * <p>直接扣减冻结余额，用于确认扣款操作。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>订单支付确认</li>
     *   <li>提现处理完成</li>
     *   <li>手续费扣除</li>
     * </ul>
     * 
     * <h4>操作逻辑：</h4>
     * <ul>
     *   <li>frozenBalance = frozenBalance - amount</li>
     *   <li>可用余额不变</li>
     *   <li>总余额减少</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @param amount 扣减的金额（分），必须大于0
     * @param version 当前版本号，用于乐观锁控制
     * @param isWithdraw 是否为提现操作，true表示同时更新totalWithdraw
     * @param isConsume 是否为消费操作，true表示同时更新totalConsume
     * @return 影响的行数，成功返回1，冻结余额不足或版本冲突返回0
     */
    int deductFrozenBalance(@Param("walletNo") String walletNo,
                            @Param("amount") Long amount,
                            @Param("version") Integer version,
                            @Param("isWithdraw") Boolean isWithdraw,
                            @Param("isConsume") Boolean isConsume,
                            @Param("balanceHash") String balanceHash);

    /**
     * 更新钱包状态
     * 
     * <p>更新钱包的状态，用于钱包的启用、冻结、关闭等操作。</p>
     * 
     * <h4>状态说明：</h4>
     * <ul>
     *   <li>ACTIVE - 正常状态，可以进行所有操作</li>
     *   <li>FROZEN - 冻结状态，只能查询，不能进行资金操作</li>
     *   <li>CLOSED - 关闭状态，钱包已关闭</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>风控冻结钱包</li>
     *   <li>用户注销关闭钱包</li>
     *   <li>管理员操作钱包</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @param status 新的状态值，不能为null
     * @param version 当前版本号，用于乐观锁控制
     * @return 影响的行数，成功返回1，版本冲突返回0
     */
    int updateStatus(@Param("walletNo") String walletNo, 
                     @Param("status") String status, 
                     @Param("version") Integer version);

    /**
     * 检查钱包编号是否存在
     * 
     * <p>检查指定的钱包编号是否已经存在，用于创建钱包时的唯一性检查。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>创建钱包前的唯一性检查</li>
     *   <li>钱包编号生成时的重复检查</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null
     * @return 存在的记录数，0表示不存在，大于0表示存在
     */
    int countByWalletNo(@Param("walletNo") String walletNo);

    /**
     * 检查用户是否已有钱包
     * 
     * <p>检查指定用户是否已经有钱包，用于创建钱包时的重复检查。</p>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>每个用户只能有一个钱包</li>
     *   <li>创建钱包前必须检查</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为null
     * @return 存在的记录数，0表示没有钱包，大于0表示已有钱包
     */
    int countByCustomerNo(@Param("customerNo") String customerNo);

    /**
     * 检查用户在指定业务类型下是否已有钱包（多租户）
     *
     * @param customerNo 用户编号
     * @param bizType 业务类型
     * @return 存在的记录数
     */
    int countByCustomerNoAndBizType(@Param("customerNo") String customerNo,
                                    @Param("bizType") String bizType);

    /**
     * 对账修复：直接设置余额和哈希（仅管理员修复使用）
     *
     * @param walletNo 钱包编号
     * @param balance 新余额（分）
     * @param balanceHash 新哈希
     * @param version 当前版本号
     * @return 影响行数
     */
    int reconcileBalance(@Param("walletNo") String walletNo,
                         @Param("balance") Long balance,
                         @Param("balanceHash") String balanceHash,
                         @Param("version") Integer version);
}
