package com.example.toilet.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MapController {

    @Value("${kakao.api.key}")
    private String kakaoApiKey;

    @GetMapping("/map")
    public String map(Model model) {
        model.addAttribute("kakaoApiKey", kakaoApiKey);
        return "map/map";
    }
}
