package com.example.toilet.service;

import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ToiletRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Service
public class ToiletService {

    @Autowired
    private ToiletRepository toiletRepository;

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @PostConstruct
    public void init() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            InputStream inputStream = new ClassPathResource(toiletDataPath.substring("classpath:".length())).getInputStream();
            JsonNode rootNode = objectMapper.readTree(inputStream);
            JsonNode dataNode = rootNode.get("DATA");

            List<Map<String, Object>> data = objectMapper.convertValue(dataNode, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> item : data) {
                try {
                    Object coordXObj = item.get("coord_x");
                    Object coordYObj = item.get("coord_y");

                    if (coordXObj != null && !coordXObj.toString().trim().isEmpty() &&
                        coordYObj != null && !coordYObj.toString().trim().isEmpty()) {

                        Toilet toilet = new Toilet();
                        toilet.setContsName((String) item.get("conts_name"));
                        toilet.setAddrNew((String) item.get("addr_new"));
                        toilet.setAddrOld((String) item.get("addr_old"));
                        toilet.setCoordX(Double.parseDouble(coordXObj.toString()));
                        toilet.setCoordY(Double.parseDouble(coordYObj.toString()));
                        toiletRepository.save(toilet);
                    }
                } catch (Exception e) {
                    // Log and continue with the next item
                    System.err.println("Skipping invalid entry: " + item);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<Toilet> getAllToilets() {
        return toiletRepository.findAll();
    }
}
