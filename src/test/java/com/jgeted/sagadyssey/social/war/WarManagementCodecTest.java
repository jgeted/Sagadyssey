package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarManagementNetwork12111.Input;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarManagementNetwork12111.Output;
import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;
import com.jgeted.sagadyssey.social.war.api.WarMapSheet;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;
import static org.junit.jupiter.api.Assertions.*;

class WarManagementCodecTest {
    private static final UUID SESSION = new UUID(1, 2);

    @Test void mapAndFormsRoundTripAsBoundedViews() {
        var map = new WarMapSheet(2, WarKey.parse("minecraft:overworld"), 64, 64, 1);
        var picker = new View(SESSION, 2, Display.PANEL, "军令", "", List.of("请选择目标"), List.of(),
                List.of("返回"), Optional.of(map));
        var form = new View(SESSION, 3, Display.PANEL, "条款", "当前有效", List.of(),
                List.of(new Field("服役天数", "7", 2)), List.of("预览"), Optional.empty());
        for (var view : List.of(picker, form)) {
            var b = Unpooled.buffer();
            try {
                Output.CODEC.encode(b, new Output(view));
                assertEquals(view, Output.CODEC.decode(b).value());
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }
    }

    @Test void onlyOfferedControlInputAndBoundedMapPixelsAreValid() {
        assertThrows(IllegalArgumentException.class, () -> new Request(SESSION, 1, 3, List.of("道路"), 4, 5));
        assertThrows(IllegalArgumentException.class, () -> new Request(SESSION, 1, MAP_POINT, List.of("伪字段"), 4, 5));
        assertThrows(IllegalArgumentException.class, () -> new Request(SESSION, 1, MAX_CONTROLS, List.of(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Request(SESSION, 1, MAP_POINT, List.of(), 128, 0));
        var request = new Request(SESSION, 7, 2, List.of("新的兵团"), 0, 0);
        var b = Unpooled.buffer();
        try {
            Input.CODEC.encode(b, new Input(request));
            assertEquals(request, Input.CODEC.decode(b).value());
            b.readerIndex(0);
            b.setByte(27, MAX_FIELDS + 1);
            assertThrows(DecoderException.class, () -> Input.CODEC.decode(b));
        } finally { b.release(); }
    }
}
