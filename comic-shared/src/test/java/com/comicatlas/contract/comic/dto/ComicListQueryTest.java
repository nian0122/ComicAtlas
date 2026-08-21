package com.comicatlas.contract.comic.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComicListQueryTest {

    @Test
    void shouldExposeStableTagCountForMyBatisBinding() {
        ComicListQuery query = new ComicListQuery();

        assertEquals(0, query.getTagCount());

        query.setTags(List.of("彩色", "无码"));

        assertEquals(2, query.getTagCount());
    }
}
