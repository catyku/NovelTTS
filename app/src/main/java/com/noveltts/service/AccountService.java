package com.noveltts.service;

import com.noveltts.domain.entity.AppUser;
import com.noveltts.domain.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 帳號管理：變更密碼、查詢登入狀態。
 *
 * 密碼一律經 {@link PasswordEncoder}（BCrypt）處理，原始密碼不落庫、不寫 log。
 */
@Service
public class AccountService {

    /** 變更密碼時要求的最小長度。 */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 變更指定帳號的密碼。
     *
     * @param currentPassword 目前密碼（用於驗證身分）
     * @param newPassword     新密碼
     * @return 錯誤訊息；null 表示成功
     */
    @Transactional
    public String changePassword(String username, String currentPassword, String newPassword) {
        Optional<AppUser> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return "帳號不存在";
        }
        AppUser user = found.get();

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return "目前密碼不正確";
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return "新密碼至少需要 " + MIN_PASSWORD_LENGTH + " 個字元";
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            return "新密碼不可與目前密碼相同";
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(OffsetDateTime.now());
        userRepository.save(user);
        return null;
    }

    /** 登入成功時更新最後登入時間。 */
    @Transactional
    public void recordLogin(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setLastLoginAt(OffsetDateTime.now());
            userRepository.save(user);
        });
    }

    /** 查詢帳號是否需要強制變更密碼。 */
    @Transactional(readOnly = true)
    public boolean mustChangePassword(String username) {
        return userRepository.findByUsername(username)
                .map(u -> Boolean.TRUE.equals(u.getMustChangePassword()))
                .orElse(false);
    }
}
