package com.conca.ontology.user.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.converter.UserEntryConverter;
import com.conca.ontology.user.entity.SysUser;
import com.conca.ontology.user.mapper.SysUserMapper;
import com.conca.ontology.user.model.UserEntry;
import com.conca.ontology.user.web.SessionUser;

/**
 * 用户管理：MySQL sys_user 唯一事实源（P1 起，由 JSON 文件迁移而来）。
 * 密码 PBKDF2WithHmacSHA256（210k 迭代 / 16B 盐 / 256bit 输出，JDK 内置零依赖），
 * 存储格式自描述：pbkdf2-sha256$迭代数$盐hex$哈希hex。
 * 初始化：不存在任何 ADMIN 用户时允许创建 admin（登录页引导设置密码）。
 * 表为 utf8mb4_bin：userId 精确匹配对齐 equals；忽略大小写查询显式 LOWER 双边。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 用户名：2-32 位字母/数字/下划线/中文 */
    private static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}$");

    private static final int MIN_PASSWORD_LEN = 6;
    private static final int MAX_PASSWORD_LEN = 64;
    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 210000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SysUserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    public UserService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    // ---------------- 查询 ----------------

    /** 是否已初始化（存在任一 ADMIN 用户；登录页据此切换"初始化/登录"模式） */
    public boolean initialized() {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserEntry.ROLE_ADMIN)) > 0;
    }

    /** 用户列表（status 为空返回全部；忽略大小写匹配；不含密码哈希） */
    public List<UserEntry> list(String status) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.apply("LOWER(status) = LOWER({0})", status);
        }
        return userMapper.selectList(qw.orderByAsc(SysUser::getUserId))
                .stream().map(UserEntryConverter::toEntry).map(UserService::withoutHash).toList();
    }

    public UserEntry findByUsername(String username) {
        String name = username == null ? "" : username.trim();
        SysUser row = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .apply("LOWER(username) = LOWER({0})", name)
                .last("LIMIT 1"));
        return row == null ? null : UserEntryConverter.toEntry(row);
    }

    /** userId 是否存在（记忆查看等场景的用户合法性预检） */
    public boolean exists(String userId) {
        return userId != null && userMapper.selectById(userId) != null;
    }

    /** 按 userId 查询用户（聊天链路取角色等场景；不存在返回 null） */
    public UserEntry findByUserId(String userId) {
        if (userId == null) {
            return null;
        }
        SysUser row = userMapper.selectById(userId);
        return row == null ? null : UserEntryConverter.toEntry(row);
    }

    // ---------------- 初始化 / 注册 / 登录 ----------------

    /** 系统初始化：创建 admin（仅当不存在任何 ADMIN 时可用，防重复初始化重置系统） */
    @Transactional
    public UserEntry initAdmin(String password) {
        if (initialized()) {
            throw new ApiException(403, "系统已初始化，禁止重复初始化");
        }
        validatePassword(password);
        SysUser admin = new SysUser();
        admin.setUserId("admin");
        admin.setUsername("admin");
        admin.setPasswordHash(hash(password));
        admin.setRole(UserEntry.ROLE_ADMIN);
        admin.setStatus(UserEntry.STATUS_ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        try {
            userMapper.insert(admin);
        } catch (DuplicateKeyException e) {
            // 并发双击兜底：唯一键冲突说明已有 admin，语义等同"已初始化"
            throw new ApiException(403, "系统已初始化，禁止重复初始化");
        }
        log.info("admin account initialized");
        return UserEntryConverter.toEntry(admin);
    }

    /** 自助注册：创建 PENDING 普通用户，需管理员审核后才能登录 */
    @Transactional
    public UserEntry register(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "用户名需 2-32 位字母/数字/下划线/中文");
        }
        validatePassword(password);
        boolean dup = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .apply("LOWER(username) = LOWER({0}) OR LOWER(user_id) = LOWER({0})", name)) > 0;
        if (dup) {
            throw new ApiException(409, "用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUserId(name);
        u.setUsername(name);
        u.setPasswordHash(hash(password));
        u.setRole(UserEntry.ROLE_USER);
        u.setStatus(UserEntry.STATUS_PENDING);
        LocalDateTime now = LocalDateTime.now();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "用户名已存在");
        }
        log.info("user registered (pending approval): {}", name);
        return UserEntryConverter.toEntry(u);
    }

    /** 登录：校验密码与状态，成功更新 lastLoginAt 并返回用户 */
    @Transactional
    public UserEntry login(String username, String password) {
        UserEntry u = findByUsername(username);
        if (u == null || !verify(password, u.passwordHash())) {
            throw new ApiException(401, "用户名或密码错误");
        }
        if (UserEntry.STATUS_PENDING.equals(u.status())) {
            throw new ApiException(403, "账号待管理员审核，暂时无法登录");
        }
        if (UserEntry.STATUS_DISABLED.equals(u.status())) {
            throw new ApiException(403, "账号已被禁用，请联系管理员");
        }
        LocalDateTime now = LocalDateTime.now();
        SysUser patch = new SysUser();
        patch.setUserId(u.userId());
        patch.setLastLoginAt(now);
        patch.setUpdatedAt(now);
        userMapper.updateById(patch);
        return UserEntryConverter.toEntry(userMapper.selectById(u.userId()));
    }

    /** 修改自己密码：验原密码 */
    @Transactional
    public void changePassword(String userId, String oldPassword, String newPassword) {
        UserEntry u = require(userId);
        if (!verify(oldPassword, u.passwordHash())) {
            throw new ApiException(400, "原密码错误");
        }
        validatePassword(newPassword);
        updateHash(userId, hash(newPassword));
        log.info("password changed: {}", userId);
    }

    // ---------------- 用户管理（ADMIN） ----------------

    /** 审核：PENDING → ACTIVE */
    @Transactional
    public void approve(String userId) {
        UserEntry u = require(userId);
        if (!UserEntry.STATUS_PENDING.equals(u.status())) {
            throw new ApiException(400, "仅待审核用户可通过审核");
        }
        updateStatusColumn(userId, UserEntry.STATUS_ACTIVE);
        log.info("user approved: {}", userId);
    }

    /** 启用 / 禁用（保护：不能操作自己、不能禁用最后一个可用管理员） */
    @Transactional
    public void setStatus(String userId, boolean enabled, SessionUser operator) {
        UserEntry u = require(userId);
        ensureNotSelf(u, operator);
        if (u.isAdmin() && u.isActive() && !enabled) {
            ensureNotLastActiveAdmin(u);
        }
        String status = enabled ? UserEntry.STATUS_ACTIVE : UserEntry.STATUS_DISABLED;
        updateStatusColumn(userId, status);
        log.info("user status -> {}: {}", status, userId);
    }

    /** 管理员重置指定用户密码 */
    @Transactional
    public void resetPassword(String userId, String newPassword) {
        require(userId);
        validatePassword(newPassword);
        updateHash(userId, hash(newPassword));
        log.info("password reset by admin: {}", userId);
    }

    /** 删除用户（保护：不能删自己、不能删最后一个可用管理员） */
    @Transactional
    public void delete(String userId, SessionUser operator) {
        UserEntry u = require(userId);
        ensureNotSelf(u, operator);
        if (u.isAdmin() && u.isActive()) {
            ensureNotLastActiveAdmin(u);
        }
        userMapper.deleteById(userId);
        log.info("user deleted: {}", userId);
    }

    // ---------------- 内部 ----------------

    private UserEntry require(String userId) {
        SysUser row = userId == null ? null : userMapper.selectById(userId);
        if (row == null) {
            throw new ApiException(404, "用户不存在: " + userId);
        }
        return UserEntryConverter.toEntry(row);
    }

    private static void ensureNotSelf(UserEntry u, SessionUser operator) {
        if (u.userId().equals(operator.userId())) {
            throw new ApiException(400, "不能对当前登录账号执行该操作");
        }
    }

    private void ensureNotLastActiveAdmin(UserEntry target) {
        long activeAdmins = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserEntry.ROLE_ADMIN)
                .eq(SysUser::getStatus, UserEntry.STATUS_ACTIVE)
                .ne(SysUser::getUserId, target.userId()));
        if (activeAdmins == 0) {
            throw new ApiException(400, "系统必须保留至少一个可用的管理员账号");
        }
    }

    /** 部分更新：MyBatis-Plus 默认 NOT_NULL 策略，仅覆写已设值的列 */
    private void updateStatusColumn(String userId, String status) {
        SysUser patch = new SysUser();
        patch.setUserId(userId);
        patch.setStatus(status);
        patch.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(patch);
    }

    private void updateHash(String userId, String passwordHash) {
        SysUser patch = new SysUser();
        patch.setUserId(userId);
        patch.setPasswordHash(passwordHash);
        patch.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(patch);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LEN || password.length() > MAX_PASSWORD_LEN) {
            throw new ApiException(400, "密码长度需 " + MIN_PASSWORD_LEN + "-" + MAX_PASSWORD_LEN + " 位");
        }
    }

    private static UserEntry withoutHash(UserEntry u) {
        return new UserEntry(u.userId(), u.username(), "", u.role(), u.status(),
                u.createdAt(), u.updatedAt(), u.lastLoginAt());
    }

    // ---------------- PBKDF2 ----------------

    private String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] dk = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS);
        return "pbkdf2-sha256$" + PBKDF2_ITERATIONS + "$"
                + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(dk);
    }

    private boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
            return false;
        }
        try {
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expect = HexFormat.of().parseHex(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(expect, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 计算失败", e);
        } finally {
            spec.clearPassword();
        }
    }
}
