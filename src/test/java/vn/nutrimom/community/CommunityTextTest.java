package vn.nutrimom.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.community.service.CommunityCursor;
import vn.nutrimom.community.service.CommunityText;

class CommunityTextTest {

    @Test
    void stripsInvisibleCharactersAndExtraBlankLines() {
        assertThat(CommunityText.required("  Xin​ chào\r\n\n\n\nmẹ\u0007  ", 100, "content"))
                .isEqualTo("Xin chào\n\nmẹ");
    }

    @Test
    void rejectsBlankAfterCleaning() {
        assertThatThrownBy(() -> CommunityText.required(" ​‮ ", 100, "content"))
                .isInstanceOf(BusinessException.class);
        assertThat(CommunityText.optional("  ", 100, "note")).isNull();
    }

    @Test
    void countsEmojiAsOneCharacter() {
        String fiveEmoji = "😀😀😀😀😀";
        assertThat(CommunityText.required(fiveEmoji, 5, "content")).isEqualTo(fiveEmoji);
        assertThatThrownBy(() -> CommunityText.required(fiveEmoji + "a", 5, "content"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void excerptCutsOnCodePointBoundary() {
        assertThat(CommunityText.excerpt("ab😀cd", 3)).isEqualTo("ab😀…");
    }

    @Test
    void cursorRoundTripsAndRejectsGarbage() {
        var at = OffsetDateTime.parse("2026-10-20T10:15:30.123456Z");
        var cursor = CommunityCursor.descending(CommunityCursor.encode(at, "id-1"));
        assertThat(cursor.at()).isEqualTo(at);
        assertThat(cursor.id()).isEqualTo("id-1");
        assertThatThrownBy(() -> CommunityCursor.descending("%%%")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommunityCursor.ascending("a".repeat(500))).isInstanceOf(BusinessException.class);
    }
}
