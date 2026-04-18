package com.khanh.teashop.khanhs_tea_bot.dto.rag;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class RagDocument {
    private String id;
    private String title;
    private String text;
    private String category;
    private String source;
}