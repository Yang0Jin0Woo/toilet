package com.example.toilet.controller;

import com.example.toilet.domain.AppUser;
import com.example.toilet.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private static final String DEFAULT_REDIRECT = "/map";
    private final AppUserRepository appUserRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public static class LoginForm {
        @NotBlank
        private String username;

        @NotBlank
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class SignupForm {
        @NotBlank
        @Size(min = 4, max = 20)
        @Pattern(regexp = "^[a-zA-Z0-9_]+$")
        private String username;

        @NotBlank
        @Email
        @Size(max = 255)
        private String email;

        @NotBlank
        @Size(min = 8, max = 72)
        private String password;

        @NotBlank
        private String confirmPassword;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConfirmPassword() {
            return confirmPassword;
        }

        public void setConfirmPassword(String confirmPassword) {
            this.confirmPassword = confirmPassword;
        }
    }

    @PostMapping("/login")
    public String login(@Valid @ModelAttribute("loginForm") LoginForm form,
                        BindingResult bindingResult,
                        @RequestParam(name = "redirect", required = false) String redirect,
                        HttpServletRequest request,
                        RedirectAttributes redirectAttributes) {
        String target = safeRedirect(redirect);
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "아이디/비밀번호를 확인해주세요.");
            return "redirect:" + target;
        }

        var userOpt = appUserRepository.findByUsername(form.getUsername().trim());
        if (userOpt.isEmpty() || !passwordEncoder.matches(form.getPassword(), userOpt.get().getPasswordHash())) {
            redirectAttributes.addFlashAttribute("errorMessage", "아이디 또는 비밀번호가 올바르지 않습니다.");
            return "redirect:" + target;
        }

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SessionKeys.LOGIN_USER_ID, userOpt.get().getId());
        session.setAttribute(SessionKeys.LOGIN_USERNAME, userOpt.get().getUsername());
        redirectAttributes.addFlashAttribute("infoMessage", "로그인되었습니다.");
        return "redirect:" + target;
    }

    @PostMapping("/logout")
    public String logout(@RequestParam(name = "redirect", required = false) String redirect,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        redirectAttributes.addFlashAttribute("infoMessage", "로그아웃되었습니다.");
        return "redirect:" + safeRedirect(redirect);
    }

    @PostMapping("/account/delete")
    public String deleteAccount(@RequestParam("password") String password,
                                @RequestParam(name = "redirect", required = false) String redirect,
                                HttpServletRequest request,
                                RedirectAttributes redirectAttributes) {
        String target = safeRedirect(redirect);
        HttpSession session = request.getSession(false);
        Long userId = currentUserId(session);
        if (userId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "로그인 후 탈퇴할 수 있습니다.");
            return "redirect:" + target;
        }
        if (password == null || password.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "비밀번호를 입력해주세요.");
            return "redirect:" + target;
        }

        var userOpt = appUserRepository.findById(userId);
        if (userOpt.isEmpty()) {
            if (session != null) {
                session.invalidate();
            }
            redirectAttributes.addFlashAttribute("errorMessage", "사용자 정보를 찾을 수 없습니다.");
            return "redirect:" + target;
        }
        if (!passwordEncoder.matches(password, userOpt.get().getPasswordHash())) {
            redirectAttributes.addFlashAttribute("errorMessage", "비밀번호가 올바르지 않습니다.");
            return "redirect:" + target;
        }

        appUserRepository.deleteById(userId);
        if (session != null) {
            session.invalidate();
        }
        redirectAttributes.addFlashAttribute("infoMessage", "회원탈퇴가 완료되었습니다.");
        return "redirect:/map";
    }

    @GetMapping("/signup")
    public String signupPage(@RequestParam(name = "redirect", required = false) String redirect,
                             Model model) {
        if (!model.containsAttribute("signupForm")) {
            model.addAttribute("signupForm", new SignupForm());
        }
        model.addAttribute("redirect", safeRedirect(redirect));
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form,
                         BindingResult bindingResult,
                         @RequestParam(name = "redirect", required = false) String redirect,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        String target = safeRedirect(redirect);
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            bindingResult.rejectValue("confirmPassword", "mismatch", "비밀번호가 일치하지 않습니다.");
        }

        String username = form.getUsername() == null ? "" : form.getUsername().trim();
        String email = form.getEmail() == null ? "" : form.getEmail().trim().toLowerCase();

        if (!username.isEmpty() && appUserRepository.existsByUsername(username)) {
            bindingResult.rejectValue("username", "duplicate", "이미 사용 중인 아이디입니다.");
        }
        if (!email.isEmpty() && appUserRepository.existsByEmail(email)) {
            bindingResult.rejectValue("email", "duplicate", "이미 사용 중인 이메일입니다.");
        }
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.signupForm", bindingResult);
            redirectAttributes.addFlashAttribute("signupForm", form);
            redirectAttributes.addFlashAttribute("errorMessage", "회원가입 정보를 확인해주세요.");
            return "redirect:/signup?redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        AppUser saved = appUserRepository.save(user);

        HttpSession session = request.getSession(true);
        session.setAttribute(SessionKeys.LOGIN_USER_ID, saved.getId());
        session.setAttribute(SessionKeys.LOGIN_USERNAME, saved.getUsername());
        redirectAttributes.addFlashAttribute("infoMessage", "회원가입이 완료되었습니다.");
        return "redirect:" + target;
    }

    private String safeRedirect(String raw) {
        return RedirectSanitizer.toSafePath(raw, DEFAULT_REDIRECT);
    }

    private Long currentUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SessionKeys.LOGIN_USER_ID);
        if (value instanceof Long v) {
            return v;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
