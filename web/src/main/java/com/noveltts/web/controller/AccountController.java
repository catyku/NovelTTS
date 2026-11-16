package com.noveltts.web.controller;

import com.noveltts.service.AccountService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 帳號密碼管理。
 *
 * 變更成功後清除 session 內的「需變更密碼」狀態，讓使用者可繼續操作。
 */
@Controller
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/account/password")
    public String passwordPage(Authentication authentication, Model model) {
        String username = authentication != null ? authentication.getName() : "";
        // 直接開此頁時也要顯示「必須變更」提示，不依賴 query 參數。
        boolean required = username != null && accountService.mustChangePassword(username);
        model.addAttribute("required", required);
        model.addAttribute("username", username);
        return "account-password";
    }

    @PostMapping("/do-change-password")
    public String changePassword(Authentication authentication,
                                 @RequestParam("currentPassword") String currentPassword,
                                 @RequestParam("newPassword") String newPassword,
                                 @RequestParam("confirmPassword") String confirmPassword,
                                 Model model) {
        String username = authentication != null ? authentication.getName() : null;

        if (!newPassword.equals(confirmPassword)) {
            return fail(model, username, "兩次輸入的新密碼不一致", true);
        }

        String error = accountService.changePassword(username, currentPassword, newPassword);
        if (error != null) {
            return fail(model, username, error, true);
        }

        model.addAttribute("success", "密碼已更新");
        model.addAttribute("username", username);
        model.addAttribute("required", false);
        return "account-password";
    }

    private String fail(Model model, String username, String error, boolean required) {
        model.addAttribute("error", error);
        model.addAttribute("username", username);
        model.addAttribute("required", required);
        return "account-password";
    }
}
