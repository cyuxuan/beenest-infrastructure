package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AttributeDefCreateRequest;
import club.beenest.iam.admin.dto.AttributeDefResponse;
import club.beenest.iam.admin.dto.AttributeDefUpdateRequest;

import java.util.List;

/**
 * 用户属性定义管理服务。
 * <p>属性定义是用户扩展属性的声明式 Schema,驱动后台属性构建器与校验。</p>
 */
public interface AttributeDefService {

    /**
     * 查询当前域全部属性定义。
     *
     * @return 属性定义列表
     */
    List<AttributeDefResponse> list();

    /**
     * 查询单个属性定义。
     *
     * @param id 属性定义主键
     * @return 属性定义
     * @throws BusinessException 属性定义不存在或不属于当前域
     */
    AttributeDefResponse get(Long id);

    /**
     * 新建属性定义。
     * <p>属性键在域内唯一,类型限 STRING/NUMBER/BOOLEAN/DATE/ENUM/JSON,ENUM 类型必须提供可选值。</p>
     *
     * @param request 属性定义创建参数
     * @return 新建后的属性定义
     * @throws BusinessException 属性键重复或类型、可选值不合法
     */
    AttributeDefResponse create(AttributeDefCreateRequest request);

    /**
     * 更新属性定义,仅覆盖请求中显式给出的字段。
     *
     * @param id      属性定义主键
     * @param request 属性定义更新参数
     * @return 更新后的属性定义
     * @throws BusinessException 属性定义不存在或更新后的类型不合法
     */
    AttributeDefResponse update(Long id, AttributeDefUpdateRequest request);

    /**
     * 删除属性定义。
     *
     * @param id 属性定义主键
     * @throws BusinessException 属性定义不存在或不属于当前域
     */
    void delete(Long id);
}
