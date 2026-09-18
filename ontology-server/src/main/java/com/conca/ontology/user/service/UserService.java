package com.conca.ontology.user.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.user.web.SessionUser;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.user.model.UserEntry;
import com.conca.ontology.user.model.UsersFile;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用户管理：data/users.json 唯一事实源（version + tmp 原子写 + synchronized）。
 * 密码 PBKDF2WithHmacSHA256（210k 迭代 / 16B 盐 / 256bit 输出，JDK 内置零依赖），
 * 存储格式自描述：pbkdf2-sha256$迭代数$盐hex$哈希hex。
 * 初始化：不存在任何 ADMIN 用户时允许创建 admin（登录页引导设置密码）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用户名：2-32 位字母/数字/下划线/中文 */
    private static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}$");

    private static final int MIN_PASSWORD_LEN = 6;
    private static final int MAX_PASSWORD_LEN = 64;
    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 210000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.auth.users-file:data/users.json}")
    private String usersFile;

    public UserService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------- 查询 ----------------

    /** 是否已初始化（存在任一 ADMIN 用户；登录页据此切换"初始化/登录"模式） */
    public synchronized boolean initialized() {
        return load().users().stream().anyMatch(UserEntry::isAdmin);
    }

    /** 用户列表（status 为空返回全部；不含密码哈希） */
    public synchronized List<UserEntry> list(String status) {
        return load().users().stream()
                .filter(u -> status == null || status.isBlank() || u.status().equalsIgnoreCase(status))
                .map(UserService::withoutHash)
                .toList();
    }

    public synchronized UserEntry findByUsername(String username) {
        String name = username == null ? "" : username.trim();
        return load().users().stream()
                .filter(u -> u.username().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    /** userId 是否存在（记忆查看等场景的用户合法性预检） */
    public synchronized boolean exists(String userId) {
        return load().users().stream().anyMatch(u -> u.userId().equals(userId));
    }

    /** 按 userId 查询用户（聊天链路取角色等场景；不存在返回 null） */
    public synchronized UserEntry findByUserId(String userId) {
        if (userId == null) {
            return null;
        }
        return load().users().stream()
                .filter(u -> u.userId().equals(userId))
                .findFirst().orElse(null);
    }

    // ---------------- 初始化 / 注册 / 登录 ----------------

    /** 系统初始化：创建 admin（仅当不存在任何 ADMIN 时可用，防重复初始化重置系统） */
    public synchronized UserEntry initAdmin(String password) {
        if (initialized()) {
            throw new ApiException(403, "系统已初始化，禁止重复初始化");
        }
        validatePassword(password);
        UserEntry admin = new UserEntry("admin", "admin", hash(password),
                UserEntry.ROLE_ADMIN, UserEntry.STATUS_ACTIVE, now(), now(), null);
        save(new UsersFile(1, new ArrayList<>(List.of(admin))));
        log.info("admin account initialized");
        return admin;
    }

    /** 自助注册：创建 PENDING 普通用户，需管理员审核后才能登录 */
    public synchronized UserEntry register(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME_RE.matcher(name).matches()) {
            throw new ApiException(400, "用户名需 2-32 位字母/数字/下划线/中文");
        }
        validatePassword(password);
        UsersFile uf = load();
        boolean dup = uf.users().stream()
                .anyMatch(u -> u.username().equalsIgnoreCase(name) || u.userId().equalsIgnoreCase(name));
        if (dup) {
            throw new ApiException(409, "用户名已存在");
        }
        UserEntry u = new UserEntry(name, name, hash(password),
                UserEntry.ROLE_USER, UserEntry.STATUS_PENDING, now(), now(), null);
        List<UserEntry> users = new ArrayList<>(uf.users());
        users.add(u);
        save(new UsersFile(uf.version() + 1, users));
        log.info("user registered (pending approval): {}", name);
        return u;
    }

    /** 登录：校验密码与状态，成功更新 lastLoginAt 并返回用户 */
    public synchronized UserEntry login(String username, String password) {
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
        return mutate(u.userId(), x -> new UserEntry(x.userId(), x.username(), x.passwordHash(),
                x.role(), x.status(), x.createdAt(), now(), now()));
    }

    /** 修改自己密码：验原密码 */
    public synchronized void changePassword(String userId, String oldPassword, String newPassword) {
        UserEntry u = require(userId);
        if (!verify(oldPassword, u.passwordHash())) {
            throw new ApiException(400, "原密码错误");
        }
        validatePassword(newPassword);
        mutate(userId, x -> new UserEntry(x.userId(), x.username(), hash(newPassword),
                x.role(), x.status(), x.createdAt(), now(), x.lastLoginAt()));
        log.info("password changed: {}", userId);
    }

    // ---------------- 用户管理（ADMIN） ----------------

    /** 审核：PENDING → ACTIVE */
    public synchronized void approve(String userId) {
        UserEntry u = require(userId);
        if (!UserEntry.STATUS_PENDING.equals(u.status())) {
            throw new ApiException(400, "仅待审核用户可通过审核");
        }
        mutate(userId, x -> new UserEntry(x.userId(), x.username(), x.passwordHash(),
                x.role(), UserEntry.STATUS_ACTIVE, x.createdAt(), now(), x.lastLoginAt()));
        log.info("user approved: {}", userId);
    }

    /** 启用 / 禁用（保护：不能操作自己、不能禁用最后一个可用管理员） */
    public synchronized void setStatus(String userId, boolean enabled, SessionUser operator) {
        UserEntry u = require(userId);
        ensureNotSelf(u, operator);
        if (u.isAdmin() && u.isActive() && !enabled) {
            ensureNotLastActiveAdmin(u);
        }
        String status = enabled ? UserEntry.STATUS_ACTIVE : UserEntry.STATUS_DISABLED;
        mutate(userId, x -> new UserEntry(x.userId(), x.username(), x.passwordHash(),
                x.role(), status, x.createdAt(), now(), x.lastLoginAt()));
        log.info("user status -> {}: {}", status, userId);
    }

    /** 管理员重置指定用户密码 */
    public synchronized void resetPassword(String userId, String newPassword) {
        require(userId);
        validatePassword(newPassword);
        mutate(userId, x -> new UserEntry(x.userId(), x.username(), hash(newPassword),
                x.role(), x.status(), x.createdAt(), now(), x.lastLoginAt()));
        log.info("password reset by admin: {}", userId);
    }

    /** 删除用户（保护：不能删自己、不能删最后一个可用管理员） */
    public synchronized void delete(String userId, SessionUser operator) {
        UserEntry u = require(userId);
        ensureNotSelf(u, operator);
        if (u.isAdmin() && u.isActive()) {
            ensureNotLastActiveAdmin(u);
        }
        UsersFile uf = load();
        List<UserEntry> users = new ArrayList<>(uf.users());
        users.removeIf(x -> x.userId().equals(userId));
        save(new UsersFile(uf.version() + 1, users));
        log.info("user deleted: {}", userId);
    }

    // ---------------- 内部 ----------------

    private UserEntry require(String userId) {
        UserEntry u = load().users().stream()
                .filter(x -> x.userId().equals(userId))
                .findFirst().orElse(null);
        if (u == null) {
            throw new ApiException(404, "用户不存在: " + userId);
        }
        return u;
    }

    private static void ensureNotSelf(UserEntry u, SessionUser operator) {
        if (u.userId().equals(operator.userId())) {
            throw new ApiException(400, "不能对当前登录账号执行该操作");
        }
    }

    private void ensureNotLastActiveAdmin(UserEntry target) {
        long activeAdmins = load().users().stream()
                .filter(x -> x.isAdmin() && x.isActive())
                .filter(x -> !x.userId().equals(target.userId()))
                .count();
        if (activeAdmins == 0) {
            throw new ApiException(400, "系统必须保留至少一个可用的管理员账号");
        }
    }

    /** 替换指定用户并落盘（version + 1），返回替换后的条目 */
    private UserEntry mutate(String userId, java.util.function.UnaryOperator<UserEntry> fn) {
        UsersFile uf = load();
        List<UserEntry> users = new ArrayList<>();
        UserEntry replaced = null;
        for (UserEntry u : uf.users()) {
            if (u.userId().equals(userId)) {
                replaced = fn.apply(u);
                users.add(replaced);
            } else {
                users.add(u);
            }
        }
        if (replaced == null) {
            throw new ApiException(404, "用户不存在: " + userId);
        }
        save(new UsersFile(uf.version() + 1, users));
        return replaced;
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

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
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

    // ---------------- 文件读写 ----------------

    private UsersFile load() {
        Path p = Path.of(usersFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            return UsersFile.empty();
        }
        try {
            UsersFile uf = mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), UsersFile.class);
            if (uf.users() == null) {
                return new UsersFile(uf.version(), new ArrayList<>());
            }
            return uf;
        } catch (IOException e) {
            throw new ApiException(500, "用户文件读取失败: " + e.getMessage());
        }
    }

    private void save(UsersFile uf) {
        Path p = Path.of(usersFile).toAbsolutePath().normalize();
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(uf),
                    StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, "用户文件写入失败: " + e.getMessage());
        }
    }
}
