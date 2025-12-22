package com.example.toilet.controller;

import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ToiletService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class MapController {

    private final ToiletService toiletService;

    @Value("${kakao.api.key}")
    private String kakaoApiKey;

    @GetMapping("/map")
    public String map(Model model) {
        List<Toilet> toilets = toiletService.getAllToilets();
        model.addAttribute("toilets", toilets);
        model.addAttribute("kakaoApiKey", kakaoApiKey);
        return "map/map";
    }

    @GetMapping("/map-benchmark")
    public String mapBenchmark(Model model) {
        model.addAttribute("kakaoApiKey", kakaoApiKey);
        return "map/map_benchmark";
    }
}
