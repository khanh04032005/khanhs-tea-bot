package com.khanh.teashop.khanhs_tea_bot.config;

import com.khanh.teashop.khanhs_tea_bot.dto.rag.RagDocument;
import com.khanh.teashop.khanhs_tea_bot.entity.Product;
import com.khanh.teashop.khanhs_tea_bot.service.ProductService;
import com.khanh.teashop.khanhs_tea_bot.service.QdrantService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RagDataInitializer implements CommandLineRunner {

    private final ProductService productService;
    private final QdrantService qdrantService;

    @Override
    public void run(String... args) {
        try {
            qdrantService.ensureCollection();

            List<RagDocument> docs = new ArrayList<>();

            for (Product product : productService.getAllProducts()) {
                docs.add(RagDocument.builder()
                        .id("menu-" + product.getId())
                        .title(product.getName())
                        .category(product.getCategory())
                        .source("menu")
                        .text("Mã: " + product.getId()
                                + ", Tên: " + product.getName()
                                + ", Danh mục: " + product.getCategory()
                                + ", Giá M: " + product.getPriceM()
                                + ", Giá L: " + product.getPriceL()
                                + ", Còn bán: " + product.isAvailable())
                        .build());
            }

            docs.add(RagDocument.builder()
                    .id("faq-hours")
                    .title("Giờ mở cửa")
                    .category("FAQ")
                    .source("faq")
                    .text("Quán mở cửa từ 8:00 đến 22:00 mỗi ngày.")
                    .build());

            docs.add(RagDocument.builder()
                    .id("faq-ship")
                    .title("Phí ship")
                    .category("FAQ")
                    .source("faq")
                    .text("Phí ship tùy khu vực, bot sẽ báo phí ship khi xác nhận địa chỉ.")
                    .build());

            qdrantService.upsertDocuments(docs);
            log.info("RAG data initialized: {}", docs.size());
        } catch (Exception exception) {
            log.error("RAG init failed", exception);
        }
    }
}