package com.example.toilet.controller;

import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ToiletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ToiletController {

    @Autowired
    private ToiletService toiletService;

    @GetMapping("/toilets")
    public List<Toilet> getAllToilets() {
        return toiletService.getAllToilets();
    }
}
