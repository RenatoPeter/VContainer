package hu.vzone.vcontainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VContainerFormatTest {

    @Test
    void supportsLegacyAndHexColors() {
        assertEquals("§bBlue", VContainer.formatMessage("&bBlue"));
        assertEquals("§x§5§4§D§A§F§4Hex", VContainer.formatMessage("&#54DAF4Hex"));
    }

    @Test
    void supportsMiniMessageAndLegacyColorAfterMiniMessage() {
        String formatted = VContainer.formatMessage("<gradient:#54daf4:#545eb6>asd</gradient> &7tail");

        assertTrue(formatted.contains("§7tail"));
        assertTrue(formatted.contains("a"));
        assertTrue(formatted.contains("s"));
        assertTrue(formatted.contains("d"));
    }
}
