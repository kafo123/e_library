package com.elibrary.domain.enums;

/**
 * 内容类型:电子书 / 期刊(单表 + 判别字段建模,见 DESIGN.md 取舍 T6)
 */
public enum BookType {

    EBOOK,
    JOURNAL;

    public static BookType fromOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (BookType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
