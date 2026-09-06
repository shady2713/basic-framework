package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.*;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;
import static com.basicframework.module.system.enums.LogRecordConstants.*;
import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.framework.datapermission.core.util.DataPermissionUtils;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.dal.mysql.user.AdminUserQuery;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dept.PostService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import com.basicframework.module.system.service.user.dto.UserImportResultDTO;
import com.google.common.annotations.VisibleForTesting;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.service.impl.DiffParseFunction;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 后台用户生命周期服务，统一维护账号约束、部门岗位关系及影响认证状态时的会话撤销。 */
@Service("adminUserService")
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    /**
     * 导入用户的初始密码随机源：CSPRNG
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * 导入用户的初始密码熵长度：16 字节（128 bit），编码为 32 位小写十六进制字符串
     */
    private static final int IMPORT_PASSWORD_BYTES = 16;

    private final AdminUserMapper userMapper;

    private final DeptService deptService;

    private final PostService postService;

    private final ObjectProvider<PermissionService> permissionServiceProvider;

    private final PasswordEncoder passwordEncoder;

    private final PasswordPolicy passwordPolicy;

    private final UserSessionRevocationPublisher sessionRevocationPublisher;

    private final UserPostMapper userPostMapper;

    private final Validator validator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_CREATE_SUB_TYPE,
            bizNo = "{{#user.id}}",
            success = SYSTEM_USER_CREATE_SUCCESS)
    public Long createUser(AdminUserDO user) {
        user.setUsername(ValidationUtils.normalizeUsername(user.getUsername()));
        normalizeWritableContactFields(user);
        validateUserForCreateOrUpdate(
                null, user.getUsername(), user.getMobile(), user.getEmail(), user.getDeptId(), user.getPostIds());
        user.setStatus(ObjUtil.defaultIfNull(user.getStatus(), CommonStatusEnum.ENABLE.getStatus()));
        passwordPolicy.validate(user.getPassword(), user.getUsername());
        user.setPassword(encodePassword(user.getPassword()));
        userMapper.insert(user);
        if (CollectionUtil.isNotEmpty(user.getPostIds())) {
            userPostMapper.insertBatch(convertList(
                    user.getPostIds(),
                    postId -> new UserPostDO().setUserId(user.getId()).setPostId(postId)));
        }

        LogRecordContext.putVariable("user", user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_SUB_TYPE,
            bizNo = "{{#updateObj.id}}",
            success = SYSTEM_USER_UPDATE_SUCCESS)
    public void updateUser(AdminUserDO updateObj) {
        updateObj.setPassword(null); // 特殊：此处不更新密码
        updateObj.setUsername(ValidationUtils.normalizeUsername(updateObj.getUsername()));
        normalizeWritableContactFields(updateObj);
        AdminUserDO oldUser = validateUserForCreateOrUpdate(
                updateObj.getId(),
                updateObj.getUsername(),
                updateObj.getMobile(),
                updateObj.getEmail(),
                updateObj.getDeptId(),
                updateObj.getPostIds());

        userMapper.updateById(updateObj);
        updateUserPost(updateObj);
        if (hasManagedSessionInfoChanged(oldUser, updateObj)) {
            sessionRevocationPublisher.revokeAdminSession(updateObj.getId(), USER_INFO_CHANGED);
        }

        // 避免前端未传递 avatar 字段时，操作日志 diff 误报为删除头像
        if (updateObj.getAvatar() == null) {
            updateObj.setAvatar(oldUser.getAvatar());
        }
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, oldUser);
        LogRecordContext.putVariable("user", oldUser);
    }

    private void updateUserPost(AdminUserDO updateObj) {
        Long userId = updateObj.getId();
        Set<Long> dbPostIds = convertSet(userPostMapper.selectListByUserId(userId), UserPostDO::getPostId);
        Set<Long> postIds = CollUtil.emptyIfNull(updateObj.getPostIds());
        Collection<Long> createPostIds = CollUtil.subtract(postIds, dbPostIds);
        Collection<Long> deletePostIds = CollUtil.subtract(dbPostIds, postIds);
        if (!CollectionUtil.isEmpty(createPostIds)) {
            userPostMapper.insertBatch(convertList(
                    createPostIds, postId -> new UserPostDO().setUserId(userId).setPostId(postId)));
        }
        if (!CollectionUtil.isEmpty(deletePostIds)) {
            userPostMapper.deleteByUserIdAndPostId(userId, deletePostIds);
        }
    }

    @Override
    public void updateUserLogin(Long id, String loginIp) {
        userMapper.updateById(new AdminUserDO().setId(id).setLoginIp(loginIp).setLoginDate(LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserProfile(Long id, AdminUserDO user) {
        AdminUserDO oldUser = validateUserExists(id);
        normalizeWritableContactFields(user);
        validateEmailUnique(id, user.getEmail());
        validateMobileUnique(id, user.getMobile());
        userMapper.updateById(user.setId(id));
        if (hasNicknameChanged(oldUser, user)) {
            sessionRevocationPublisher.revokeAdminSession(id, USER_INFO_CHANGED);
        }
    }

    private static boolean hasManagedSessionInfoChanged(AdminUserDO oldUser, AdminUserDO newUser) {
        return hasNicknameChanged(oldUser, newUser) || !Objects.equals(oldUser.getDeptId(), newUser.getDeptId());
    }

    private static boolean hasNicknameChanged(AdminUserDO oldUser, AdminUserDO newUser) {
        return newUser.getNickname() != null && !Objects.equals(oldUser.getNickname(), newUser.getNickname());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_OWN_PASSWORD_SUCCESS)
    public void updateUserPassword(Long id, String oldPassword, String newPassword) {
        AdminUserDO user = validateOldPassword(id, oldPassword);
        passwordPolicy.validate(newPassword, user.getUsername());
        AdminUserDO updateObj = new AdminUserDO().setId(id);
        updateObj.setPassword(encodePassword(newPassword)); // 加密密码
        userMapper.updateById(updateObj);
        sessionRevocationPublisher.revokeAdminSession(id, PASSWORD_CHANGED);
        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_PASSWORD_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_PASSWORD_SUCCESS)
    public void updateUserPassword(Long id, String password) {
        AdminUserDO user = validateUserExists(id);
        passwordPolicy.validate(password, user.getUsername());

        AdminUserDO updateObj = new AdminUserDO();
        updateObj.setId(id);
        updateObj.setPassword(encodePassword(password)); // 加密密码
        userMapper.updateById(updateObj);
        sessionRevocationPublisher.revokeAdminSession(id, PASSWORD_CHANGED);

        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_UPDATE_STATUS_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_UPDATE_STATUS_SUCCESS)
    public void updateUserStatus(Long id, Integer status) {
        AdminUserDO user = validateUserExists(id);
        AdminUserDO updateObj = new AdminUserDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        userMapper.updateById(updateObj);

        if (CommonStatusEnum.isDisable(status)) {
            sessionRevocationPublisher.revokeAdminSession(id, USER_DISABLED);
        }

        LogRecordContext.putVariable("user", user);
        LogRecordContext.putVariable(
                "statusName",
                CommonStatusEnum.isEnable(status)
                        ? CommonStatusEnum.ENABLE.getName()
                        : CommonStatusEnum.DISABLE.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_DELETE_SUB_TYPE,
            bizNo = "{{#id}}",
            success = SYSTEM_USER_DELETE_SUCCESS)
    public void deleteUser(Long id) {
        AdminUserDO user = validateUserExists(id);

        deptService.processUserDeleted(id);
        userMapper.deleteById(id);
        getPermissionService().processUserDeleted(id);
        userPostMapper.deleteByUserId(id);
        sessionRevocationPublisher.revokeAdminSession(id, USER_DELETED);

        LogRecordContext.putVariable("user", user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserList(List<Long> ids) {
        ids.forEach(deptService::processUserDeleted);
        userMapper.deleteByIds(ids);

        ids.forEach(id -> {
            getPermissionService().processUserDeleted(id);
            userPostMapper.deleteByUserId(id);
        });
        sessionRevocationPublisher.revokeAdminSessions(ids, USER_DELETED);
    }

    @Override
    public AdminUserDO getUserByUsername(String username) {
        return userMapper.selectByUsername(ValidationUtils.normalizeUsername(username));
    }

    @Override
    public AdminUserDO getUserByMobile(String mobile) {
        return userMapper.selectByMobile(ValidationUtils.normalizeMobile(mobile));
    }

    @Override
    public PageResult<AdminUserDO> getUserPage(PageParam pageParam, AdminUserQuery query, Long deptId, Long roleId) {
        Set<Long> userIds = null;
        if (roleId != null) {
            userIds = getPermissionService().getUserRoleIdListByRoleId(singleton(roleId));
            if (CollUtil.isEmpty(userIds)) {
                return PageResult.empty();
            }
        }

        return userMapper.selectPage(
                pageParam,
                new AdminUserQuery(
                        query.getUsername(),
                        query.getMobile(),
                        query.getStatus(),
                        query.getCreateTime(),
                        getDeptCondition(deptId),
                        userIds));
    }

    @Override
    public AdminUserDO getUser(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public List<AdminUserDO> getUserListByDeptIds(Collection<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return Collections.emptyList();
        }
        return userMapper.selectListByDeptIds(deptIds);
    }

    @Override
    public List<AdminUserDO> getUserListByPostIds(Collection<Long> postIds) {
        if (CollUtil.isEmpty(postIds)) {
            return Collections.emptyList();
        }
        Set<Long> userIds = convertSet(userPostMapper.selectListByPostIds(postIds), UserPostDO::getUserId);
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyList();
        }
        return userMapper.selectByIds(userIds);
    }

    @Override
    public List<AdminUserDO> getUserList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return userMapper.selectByIds(ids);
    }

    @Override
    public void validateUserList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        List<AdminUserDO> users = userMapper.selectByIds(ids);
        Map<Long, AdminUserDO> userMap = CollectionUtils.convertMap(users, AdminUserDO::getId);
        ids.forEach(id -> {
            AdminUserDO user = userMap.get(id);
            if (user == null) {
                throw exception(USER_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
                throw exception(USER_IS_DISABLE, user.getNickname());
            }
        });
    }

    @Override
    public List<AdminUserDO> getUserListByNickname(String nickname) {
        return userMapper.selectListByNickname(ValidationUtils.normalizeNickname(nickname));
    }

    /**
     * 获得部门条件：查询指定部门的子部门编号们，包括自身
     *
     * 走缓存版子部门查询（{@link DeptService#getChildDeptIdListFromCache(Long)}），
     * 避免用户分页等高频入口逐层递归查库；部门写操作会清空该缓存。
     *
     * @param deptId 部门编号
     * @return 部门编号集合
     */
    private Set<Long> getDeptCondition(Long deptId) {
        if (deptId == null) {
            return Collections.emptySet();
        }
        // 复制一份再并入自身，避免改动缓存返回的集合实例
        Set<Long> deptIds = new HashSet<>(deptService.getChildDeptIdListFromCache(deptId));
        deptIds.add(deptId); // 包括自身
        return deptIds;
    }

    private AdminUserDO validateUserForCreateOrUpdate(
            Long id, String username, String mobile, String email, Long deptId, Set<Long> postIds) {
        // 关闭数据权限，避免因为没有数据权限，查询不到数据，进而导致唯一校验不正确
        return DataPermissionUtils.executeIgnore(() -> {
            AdminUserDO user = validateUserExists(id);
            validateUsernameUnique(id, username);
            validateMobileUnique(id, mobile);
            validateEmailUnique(id, email);
            deptService.validateDeptList(CollectionUtils.singleton(deptId));
            postService.validatePostList(postIds);
            return user;
        });
    }

    @VisibleForTesting
    AdminUserDO validateUserExists(Long id) {
        if (id == null) {
            return null;
        }
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        return user;
    }

    @VisibleForTesting
    void validateUsernameUnique(Long id, String username) {
        String normalizedUsername = ValidationUtils.normalizeUsername(username);
        if (StrUtil.isBlank(normalizedUsername)) {
            return;
        }
        AdminUserDO user = userMapper.selectByUsername(normalizedUsername);
        if (user == null) {
            return;
        }
        if (id == null) {
            throw exception(USER_USERNAME_EXISTS);
        }
        if (!user.getId().equals(id)) {
            throw exception(USER_USERNAME_EXISTS);
        }
    }

    @VisibleForTesting
    void validateEmailUnique(Long id, String email) {
        String normalizedEmail = ValidationUtils.normalizeEmail(email);
        if (StrUtil.isBlank(normalizedEmail)) {
            return;
        }
        AdminUserDO user = userMapper.selectByEmail(normalizedEmail);
        if (user == null) {
            return;
        }
        if (id == null) {
            throw exception(USER_EMAIL_EXISTS);
        }
        if (!user.getId().equals(id)) {
            throw exception(USER_EMAIL_EXISTS);
        }
    }

    @VisibleForTesting
    void validateMobileUnique(Long id, String mobile) {
        String normalizedMobile = ValidationUtils.normalizeMobile(mobile);
        if (StrUtil.isBlank(normalizedMobile)) {
            return;
        }
        AdminUserDO user = userMapper.selectByMobile(normalizedMobile);
        if (user == null) {
            return;
        }
        if (id == null) {
            throw exception(USER_MOBILE_EXISTS);
        }
        if (!user.getId().equals(id)) {
            throw exception(USER_MOBILE_EXISTS);
        }
    }

    /**
     * 校验旧密码
     * @param id          用户 id
     * @param oldPassword 旧密码
     */
    @VisibleForTesting
    AdminUserDO validateOldPassword(Long id, String oldPassword) {
        AdminUserDO user = userMapper.selectById(id);
        if (user == null) {
            throw exception(USER_NOT_EXISTS);
        }
        if (!isPasswordMatch(oldPassword, user.getPassword())) {
            throw exception(USER_PASSWORD_FAILED);
        }
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 添加事务，异常则回滚所有导入
    public UserImportResultDTO importUserList(List<UserImportDTO> importUsers, boolean isUpdateSupport) {
        if (CollUtil.isEmpty(importUsers)) {
            throw exception(USER_IMPORT_LIST_IS_EMPTY);
        }
        // 不再读取共享初始密码（system.user.init-password 配置已删除）：
        // 导入的新用户为禁用状态 + CSPRNG 随机密码，需管理员重置密码并启用后激活

        List<String> createUsernames = new ArrayList<>();
        List<String> updateUsernames = new ArrayList<>();
        Map<String, String> failureUsernames = new LinkedHashMap<>();
        AtomicInteger index = new AtomicInteger(1);
        importUsers.forEach(importUser -> {
            int currentIndex = index.getAndIncrement();
            importUser.setUsername(ValidationUtils.normalizeUsername(importUser.getUsername()));
            importUser.setNickname(ValidationUtils.normalizeNickname(importUser.getNickname()));
            importUser.setMobile(ValidationUtils.normalizeMobile(importUser.getMobile()));
            importUser.setEmail(ValidationUtils.normalizeEmail(importUser.getEmail()));
            // 每个新用户独立的随机初始密码：明文不落库，仅用于通过新增校验后加密入库
            String randomPassword = generateImportPassword();
            try {
                ValidationUtils.validate(validator, importUser);
            } catch (ConstraintViolationException ex) {
                String key = StrUtil.blankToDefault(importUser.getUsername(), "第 " + currentIndex + " 行");
                failureUsernames.put(key, firstConstraintViolationMessage(ex));
                return;
            }
            Long deptId = null;
            if (StrUtil.isNotBlank(importUser.getDeptName())) {
                DeptDO dept = deptService.getDeptByName(importUser.getDeptName());
                if (dept == null) {
                    failureUsernames.put(importUser.getUsername(), "部门名称不存在");
                    return;
                }
                deptId = dept.getId();
            }

            try {
                validateUserForCreateOrUpdate(null, null, importUser.getMobile(), importUser.getEmail(), deptId, null);
            } catch (ServiceException ex) {
                failureUsernames.put(importUser.getUsername(), ex.getPublicMessage());
                return;
            }

            AdminUserDO existUser = userMapper.selectByUsername(importUser.getUsername());
            if (existUser == null) {
                // 新用户强制禁用状态 + 随机密码（忽略 Excel 中的账号状态列），
                // 由管理员通过“重置密码”功能分配新密码并启用后，账号才可登录
                userMapper.insert(BeanUtils.toBean(importUser, AdminUserDO.class)
                        .setDeptId(deptId)
                        .setPassword(encodePassword(randomPassword))
                        .setStatus(CommonStatusEnum.DISABLE.getStatus())
                        .setPostIds(new HashSet<>())); // 空岗位编号数组
                createUsernames.add(importUser.getUsername());
                return;
            }
            if (!isUpdateSupport) {
                failureUsernames.put(importUser.getUsername(), USER_USERNAME_EXISTS.getMsg());
                return;
            }
            AdminUserDO updateUser = BeanUtils.toBean(importUser, AdminUserDO.class);
            updateUser.setId(existUser.getId());
            updateUser.setDeptId(deptId);
            userMapper.updateById(updateUser);
            updateUsernames.add(importUser.getUsername());
        });
        return new UserImportResultDTO(createUsernames, updateUsernames, failureUsernames);
    }

    @VisibleForTesting
    static String firstConstraintViolationMessage(ConstraintViolationException exception) {
        return exception.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("用户信息校验失败");
    }

    @Override
    public List<AdminUserDO> getUserListByStatus(Integer status) {
        return userMapper.selectListByStatus(status);
    }

    @Override
    public boolean isPasswordMatch(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    @Override
    public void upgradePasswordEncodingIfNeeded(Long id, String rawPassword, String expectedEncodedPassword) {
        if (!passwordEncoder.upgradeEncoding(expectedEncodedPassword)) {
            return;
        }
        String upgradedPassword = encodePassword(rawPassword);
        userMapper.updatePasswordIfUnchanged(id, expectedEncodedPassword, upgradedPassword);
    }

    private PermissionService getPermissionService() {
        return permissionServiceProvider.getObject();
    }

    private static void normalizeWritableContactFields(AdminUserDO user) {
        user.setNickname(ValidationUtils.normalizeNickname(user.getNickname()));
        user.setMobile(ValidationUtils.normalizeMobile(user.getMobile()));
        user.setEmail(ValidationUtils.normalizeEmail(user.getEmail()));
    }

    /**
     * 对密码进行加密
     *
     * @param password 密码
     * @return 加密后的密码
     */
    private String encodePassword(String password) {
        return passwordEncoder.encode(password);
    }

    /**
     * 使用 CSPRNG 生成导入用户的初始密码：16 字节（128 bit）随机数，
     * 编码为 32 位小写十六进制字符串。生成方式与用户会话令牌一致。
     *
     * @return 32 位小写十六进制随机密码
     */
    private static String generateImportPassword() {
        byte[] bytes = new byte[IMPORT_PASSWORD_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexUtil.encodeHexStr(bytes);
    }
}
