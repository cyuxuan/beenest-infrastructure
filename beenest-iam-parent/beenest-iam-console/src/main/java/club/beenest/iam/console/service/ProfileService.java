package club.beenest.iam.console.service;

import club.beenest.iam.console.dto.ChangePasswordRequest;
import club.beenest.iam.console.dto.ConsoleProfileResponse;
import club.beenest.iam.console.dto.ProfileUpdateRequest;

/**
 * 个人档案服务。
 * <p>自助端只操作调用者自己的数据,越权由服务层比对用户主键拦下,不依赖权限码。</p>
 */
public interface ProfileService {

    /**
     * 查询本人档案。
     *
     * @return 本人档案
     */
    ConsoleProfileResponse get();

    /**
     * 修改本人档案。
     *
     * @param request 档案修改请求
     * @return 修改后的档案
     */
    ConsoleProfileResponse update(ProfileUpdateRequest request);

    /**
     * 修改本人登录口令,需校验原口令。
     *
     * @param request 改密请求
     */
    void changePassword(ChangePasswordRequest request);
}
