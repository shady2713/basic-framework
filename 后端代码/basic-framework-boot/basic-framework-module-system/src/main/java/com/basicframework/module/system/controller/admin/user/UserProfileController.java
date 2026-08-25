package com.basicframework.module.system.controller.admin.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_PASSWORD_FAILED;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import com.basicframework.framework.security.core.annotation.MfaStepUp;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTotpSetupRespVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaTotpVerifyReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaWebAuthnFinishReqVO;
import com.basicframework.module.system.controller.admin.auth.vo.AuthMfaWebAuthnOptionsRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileMfaEnrollmentStartReqVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileMfaFactorRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileMfaRecoveryCodesRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileRespVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import com.basicframework.module.system.convert.user.UserConvert;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.service.auth.MfaFactorManagementService;
import com.basicframework.module.system.service.auth.MfaService;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.user.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理后台 - 用户个人中心")
@RestController
@RequestMapping("/system/user/profile")
@Validated
@Slf4j
public class UserProfileController {

    @Resource
    private AdminUserService userService;

    @Resource
    private DeptService deptService;

    @Resource
    private PostService postService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private RoleService roleService;

    @Resource
    private MfaService mfaService;

    @Resource
    private MfaFactorManagementService mfaFactorManagementService;

    @GetMapping("/get")
    @Operation(summary = "获得登录用户信息")
    @DataPermission(enable = false) // 关闭数据权限，避免只查看自己时，查询不到部门。
    public CommonResult<UserProfileRespVO> getUserProfile() {
        // 获得用户基本信息
        AdminUserDO user = userService.getUser(getLoginUserId());
        // 获得用户角色
        List<RoleDO> userRoles =
                roleService.getRoleListFromCache(permissionService.getUserRoleIdListByUserId(user.getId()));
        // 获得部门信息
        DeptDO dept = user.getDeptId() != null ? deptService.getDept(user.getDeptId()) : null;
        // 获得岗位信息
        List<PostDO> posts = CollUtil.isNotEmpty(user.getPostIds()) ? postService.getPostList(user.getPostIds()) : null;
        return success(UserConvert.INSTANCE.convert(user, userRoles, dept, posts));
    }

    @PutMapping("/update")
    @Operation(summary = "修改用户个人信息")
    @MfaStepUp
    public CommonResult<Boolean> updateUserProfile(@Valid @RequestBody UserProfileUpdateReqVO reqVO) {
        userService.updateUserProfile(getLoginUserId(), BeanUtils.toBean(reqVO, AdminUserDO.class));
        return success(true);
    }

    @PutMapping("/update-password")
    @Operation(summary = "修改用户个人密码")
    @MfaStepUp
    public CommonResult<Boolean> updateUserProfilePassword(@Valid @RequestBody UserProfileUpdatePasswordReqVO reqVO) {
        userService.updateUserPassword(getLoginUserId(), reqVO.getOldPassword(), reqVO.getNewPassword());
        return success(true);
    }

    @GetMapping("/mfa/methods")
    @Operation(summary = "查询当前用户已启用的 MFA 方法")
    public CommonResult<List<String>> getMfaMethods() {
        return success(mfaService.getEnabledMethods(getLoginUserId()));
    }

    @GetMapping("/mfa/enrollment-methods")
    @Operation(summary = "查询当前部署允许注册的 MFA 方法")
    public CommonResult<List<String>> getMfaEnrollmentMethods() {
        return success(mfaService.getEnrollmentMethods());
    }

    @GetMapping("/mfa/factors")
    @Operation(summary = "查询当前用户可管理的 MFA 因子")
    public CommonResult<List<UserProfileMfaFactorRespVO>> getMfaFactors() {
        return success(BeanUtils.toBean(
                mfaFactorManagementService.getFactors(getLoginUserId()), UserProfileMfaFactorRespVO.class));
    }

    @PostMapping("/mfa/totp/enroll/start")
    @Operation(summary = "开始当前用户 TOTP 自助注册")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"password", "enrollmentToken", "secret", "otpauthUri"})
    public CommonResult<AuthMfaTotpSetupRespVO> startMfaTotpEnrollment(
            @Valid @RequestBody UserProfileMfaEnrollmentStartReqVO reqVO, HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        AdminUserDO user = requireCurrentPassword(reqVO.getPassword());
        String enrollmentChallenge = mfaService.beginSelfEnrollment(user.getId(), user.getUsername());
        return success(BeanUtils.toBean(
                mfaService.beginRequiredTotpEnrollment(enrollmentChallenge), AuthMfaTotpSetupRespVO.class));
    }

    @PostMapping("/mfa/totp/enroll/finish")
    @Operation(summary = "完成当前用户 TOTP 自助注册")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "code", "recoveryCodes"})
    public CommonResult<UserProfileMfaRecoveryCodesRespVO> finishMfaTotpEnrollment(
            @Valid @RequestBody AuthMfaTotpVerifyReqVO reqVO, HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        return success(new UserProfileMfaRecoveryCodesRespVO(mfaService
                .completeSelfTotpEnrollment(getLoginUserId(), reqVO.getMfaToken(), reqVO.getCode())
                .getRecoveryCodes()));
    }

    @PostMapping("/mfa/webauthn/enroll/start")
    @Operation(summary = "开始当前用户 WebAuthn 自助注册")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"password", "ceremonyToken", "optionsJson"})
    public CommonResult<AuthMfaWebAuthnOptionsRespVO> startMfaWebAuthnEnrollment(
            @Valid @RequestBody UserProfileMfaEnrollmentStartReqVO reqVO, HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        AdminUserDO user = requireCurrentPassword(reqVO.getPassword());
        String enrollmentChallenge = mfaService.beginSelfEnrollment(user.getId(), user.getUsername());
        return success(BeanUtils.toBean(
                mfaService.beginRequiredWebAuthnEnrollment(enrollmentChallenge), AuthMfaWebAuthnOptionsRespVO.class));
    }

    @PostMapping("/mfa/webauthn/enroll/finish")
    @Operation(summary = "完成当前用户 WebAuthn 自助注册")
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"ceremonyToken", "credentialJson", "recoveryCodes"})
    public CommonResult<UserProfileMfaRecoveryCodesRespVO> finishMfaWebAuthnEnrollment(
            @Valid @RequestBody AuthMfaWebAuthnFinishReqVO reqVO, HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        return success(new UserProfileMfaRecoveryCodesRespVO(mfaService
                .completeSelfWebAuthnEnrollment(getLoginUserId(), reqVO.getCeremonyToken(), reqVO.getCredentialJson())
                .getRecoveryCodes()));
    }

    @PostMapping("/mfa/manage/totp/enroll/start")
    @Operation(summary = "开始新增或轮换当前用户 TOTP 因子")
    @MfaStepUp
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"enrollmentToken", "secret", "otpauthUri"})
    public CommonResult<AuthMfaTotpSetupRespVO> startManagedTotpEnrollment(HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        AdminUserDO user = userService.getUser(getLoginUserId());
        return success(BeanUtils.toBean(
                mfaFactorManagementService.beginTotpEnrollment(user.getId(), user.getUsername()),
                AuthMfaTotpSetupRespVO.class));
    }

    @PostMapping("/mfa/manage/totp/enroll/finish")
    @Operation(summary = "完成新增或轮换当前用户 TOTP 因子")
    @MfaStepUp
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"mfaToken", "code", "recoveryCodes"})
    public CommonResult<UserProfileMfaRecoveryCodesRespVO> finishManagedTotpEnrollment(
            @Valid @RequestBody AuthMfaTotpVerifyReqVO reqVO, HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        return success(new UserProfileMfaRecoveryCodesRespVO(mfaFactorManagementService.completeTotpEnrollment(
                getLoginUserId(), reqVO.getMfaToken(), reqVO.getCode())));
    }

    @PostMapping("/mfa/manage/webauthn/enroll/start")
    @Operation(summary = "开始新增当前用户 WebAuthn 因子")
    @MfaStepUp
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"ceremonyToken", "optionsJson"})
    public CommonResult<AuthMfaWebAuthnOptionsRespVO> startManagedWebAuthnEnrollment(HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        AdminUserDO user = userService.getUser(getLoginUserId());
        return success(BeanUtils.toBean(
                mfaFactorManagementService.beginWebAuthnEnrollment(user.getId(), user.getUsername()),
                AuthMfaWebAuthnOptionsRespVO.class));
    }

    @PostMapping("/mfa/manage/webauthn/enroll/finish")
    @Operation(summary = "完成新增当前用户 WebAuthn 因子")
    @MfaStepUp
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"ceremonyToken", "credentialJson"})
    public CommonResult<Boolean> finishManagedWebAuthnEnrollment(@Valid @RequestBody AuthMfaWebAuthnFinishReqVO reqVO) {
        mfaFactorManagementService.completeWebAuthnEnrollment(
                getLoginUserId(), reqVO.getCeremonyToken(), reqVO.getCredentialJson());
        return success(true);
    }

    @DeleteMapping("/mfa/factors/{factorId}")
    @Operation(summary = "移除当前用户 MFA 因子")
    @MfaStepUp
    @RateLimiter(time = 60, count = 10, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    public CommonResult<Boolean> removeMfaFactor(@PathVariable("factorId") @Positive Long factorId) {
        mfaFactorManagementService.removeFactor(getLoginUserId(), factorId);
        return success(true);
    }

    @PostMapping("/mfa/recovery-codes/reset")
    @Operation(summary = "重置当前用户 MFA 恢复码")
    @MfaStepUp
    @RateLimiter(time = 60, count = 5, message = "操作过于频繁，请稍后重试", keyResolver = ClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(sanitizeKeys = {"recoveryCodes"})
    public CommonResult<UserProfileMfaRecoveryCodesRespVO> resetMfaRecoveryCodes(HttpServletResponse response) {
        disableSensitiveResponseCaching(response);
        return success(
                new UserProfileMfaRecoveryCodesRespVO(mfaFactorManagementService.resetRecoveryCodes(getLoginUserId())));
    }

    private AdminUserDO requireCurrentPassword(String password) {
        AdminUserDO user = userService.getUser(getLoginUserId());
        if (user == null || !userService.isPasswordMatch(password, user.getPassword())) {
            throw exception(USER_PASSWORD_FAILED);
        }
        return user;
    }

    private static void disableSensitiveResponseCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
