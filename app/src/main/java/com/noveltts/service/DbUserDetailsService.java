package com.noveltts.service;

import com.noveltts.domain.entity.AppUser;
import com.noveltts.domain.repository.AppUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 從資料庫載入帳號，取代原本寫在設定檔的明文帳密。
 *
 * 密碼以 BCrypt 雜湊比對；停用帳號（enabled = false）一律拒絕登入。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public DbUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("帳號不存在"));

        boolean enabled = Boolean.TRUE.equals(user.getEnabled());
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));

        // User.withUsername 的第一個參數是 username，第二個是「不需加密的佔位密碼」，
        // 實際驗證時 Spring Security 會用 passwordEncoder 比對 getPassword()，
        // 因此這裡直接放 BCrypt 雜湊。
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .disabled(!enabled)
                .build();
    }
}
