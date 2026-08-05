package ac;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class PlayerDataTest {
    @Test
    public void violationValuesAreClampedAndDecay() {
        PlayerData data = new PlayerData(UUID.randomUUID());

        data.addViolation("speed", 5);
        data.addViolation("speed", -20);

        assertEquals(0, data.getViolationLevel("speed"));
        assertEquals(0, data.getTotalViolations());

        data.addViolation("flight", 3);
        data.reduceViolationLevels();
        assertEquals(2, data.getViolationLevel("flight"));
    }

    @Test
    public void attackHistoryIsBoundedToFiveSeconds() {
        PlayerData data = new PlayerData(UUID.randomUUID());

        for (int i = 0; i < 4; i++) {
            data.recordAttack();
        }

        assertEquals(4.0D, data.getCPS(), 0.001D);
    }
}
