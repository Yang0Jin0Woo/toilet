package com.example.toilet.service;

import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ToiletService {

    private final ToiletRepository toiletRepository;
    private final ReviewRepository reviewRepository;

    @Value("${toilet.data.path}")
    private String toiletDataPath;

    @PostConstruct
    public void init() {
        try {
            if (toiletRepository.count() > 0) {
                // System.out.println("초기 데이터 입력 작업 생략됨. 화장실=" + toiletRepository.count());
                return;
            }

            ObjectMapper objectMapper = new ObjectMapper();
            InputStream inputStream =
                    new ClassPathResource(toiletDataPath.substring("classpath:".length())).getInputStream();
            JsonNode rootNode = objectMapper.readTree(inputStream);
            JsonNode dataNode = rootNode.get("DATA");

            List<Map<String, Object>> data =
                    objectMapper.convertValue(dataNode, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> item : data) {
                try {
                    Object coordXObj = item.get("coord_x");
                    Object coordYObj = item.get("coord_y");
                    if (coordXObj == null || coordYObj == null) continue;
                    String sx = coordXObj.toString().trim();
                    String sy = coordYObj.toString().trim();
                    if (sx.isEmpty() || sy.isEmpty()) continue;

                    Toilet incoming = new Toilet();
                    incoming.setContsName((String) item.get("conts_name"));
                    incoming.setAddrNew((String) item.get("addr_new"));
                    incoming.setAddrOld((String) item.get("addr_old"));
                    incoming.setCoordX(Double.parseDouble(sx));
                    incoming.setCoordY(Double.parseDouble(sy));
                    incoming.setValue04((String) item.get("value_04"));
                    incoming.setValue05((String) item.get("value_05"));

                    String externalId = buildExternalId(
                            incoming.getContsName(),
                            incoming.getAddrNew(),
                            incoming.getCoordX(),
                            incoming.getCoordY()
                    );
                    incoming.setExternalId(externalId);

                    upsert(incoming);

                } catch (Exception ignore) {
                    System.err.println("잘못된 데이터로 인해 건너뜀: " + item);
                    ignore.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void upsert(Toilet incoming) {
        var opt = toiletRepository.findByExternalId(incoming.getExternalId());
        if (opt.isPresent()) {
            Toilet t = opt.get();
            t.setContsName(incoming.getContsName());
            t.setAddrNew(incoming.getAddrNew());
            t.setAddrOld(incoming.getAddrOld());
            t.setCoordX(incoming.getCoordX());
            t.setCoordY(incoming.getCoordY());
            t.setValue04(incoming.getValue04());
            t.setValue05(incoming.getValue05());
            toiletRepository.save(t);
        } else {
            toiletRepository.save(incoming); // 최초 insert
        }
    }

    private static String buildExternalId(String name, String addrNew, Double x, Double y) {
        String key = (name == null ? "" : name.trim()) + "|"
                + (addrNew == null ? "" : addrNew.trim()) + "|"
                + String.format(java.util.Locale.US, "%.6f,%.6f", x, y);
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "FALLBACK_" + key.replace(' ', '_');
        }
    }

    public List<Toilet> getAllToilets() { return toiletRepository.findAll(); }

    public List<Toilet> findAllWithRatings() {
        List<Toilet> toilets = toiletRepository.findAll();
        if (toilets.isEmpty()) return toilets;

        List<Long> ids = toilets.stream().map(Toilet::getId).collect(java.util.stream.Collectors.toList());
        var aggs = reviewRepository.aggregateByToiletIds(ids);
        Map<Long, ReviewRepository.ToiletRatingAgg> aggMap =
                aggs.stream().collect(java.util.stream.Collectors.toMap(
                        ReviewRepository.ToiletRatingAgg::getToiletId, a -> a));

        for (Toilet t : toilets) {
            var a = aggMap.get(t.getId());
            t.setAvgRating(a != null && a.getAvg() != null ? a.getAvg() : 0.0);
            t.setReviewCount(a != null ? a.getCnt() : 0L);
        }
        return toilets;
    }

    public Optional<Toilet> findById(Long id) { return toiletRepository.findById(id); }
}
