package com.noveltts.web.controller;

import com.noveltts.service.AccountService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 帳號密碼管理。
 *
 * 單一路徑 {@code /do-change-password} 同時負責顯示表單（GET）與提交變更（POST），
 * 避免顯示頁與 action 分成兩個 URL 造成使用者困惑。
 *
 * 「必須變更密碼」的狀態一律從資料庫判斷，不使用 query 參數，
 * 因此直接開啟此頁與被導向此頁的行為一致。
 */
@Controller
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 顯示變更密碼表單。 */
    @GetMapping("/do-change-password")
    public String passwordPage(Authentication authentication, Model model) {
        String username = authentication != null ? authentication.getName() : "";
        // 直接開此頁時也要顯示「必須變更」提示，不依賴 query 參數。
        boolean required = username != null && accountService.mustChangePassword(username);
        model.addAttribute("required", required);
        model.addAttribute("username", username);
        return "account-password";
    }

    /** 提交密碼變更。 */
    @PostMapping("/do-change-password")
    public String changePassword(Authentication authentication,
                                 @RequestParam("currentPassword") String currentPassword,
                                 @RequestParam("newPassword") String newPassword,
                                 @RequestParam("confirmPassword") String confirmPassword,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        String username = authentication != null ? authentication.getName() : null;

        if (!newPassword.equals(confirmPassword)) {
            return fail(model, username, "兩次輸入的新密碼不一致");
        }

        String error = accountService.changePassword(username, currentPassword, newPassword);
        if (error != null) {
            return fail(model, username, error);
        }

        // 成功後導回首頁（POST-Redirect-GET）：
        // 直接 render 同一個 view 會讓瀏覽器停留在 POST 的網址，
        // 重新整理會再送出一次表單。用 flash attribute 帶提示，
        // 重新整理首頁不會重複顯示。
        redirectAttributes.addFlashAttribute("passwordChanged", true);
        return "redirect:/";
    }

    private String fail(Model model, String username, String error) {
        model.addAttribute("error", error);
        model.addAttribute("username", username);
        // 失敗時仍依資料庫判定，避免已改完密碼後重送舊表單誤導。
        model.addAttribute("required",
                username != null && accountService.mustChangePassword(username));
        return "account-password";
    }
}
