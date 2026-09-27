package VLADMIX90.motorcyclePlugin;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;

import java.util.UUID;

public final class Motorcycle {
    private final UUID id;
    private final ArmorStand seat;
    private final ItemDisplay visual;

    private double speed;
    private int battery;
    private boolean storageInProgress;

    /** Направление корпуса/руля мотоцикла. Не зависит от камеры игрока. */
    private float headingYaw;

    private float visualRoll;
    private float visualPitch;
    private final double hoverPhase;

    private double jumpVelocity;
    private boolean jumping;
    private boolean jumpHeld;

    public Motorcycle(UUID id, ArmorStand seat, ItemDisplay visual, int battery, float headingYaw) {
        this.id = id;
        this.seat = seat;
        this.visual = visual;
        this.battery = battery;
        this.headingYaw = headingYaw;

        long mixed = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        this.hoverPhase = (mixed & 0xFFFFL) / 65535.0 * Math.PI * 2.0;
    }

    public UUID id() { return id; }
    public ArmorStand seat() { return seat; }
    public ItemDisplay visual() { return visual; }

    public double speed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public int battery() { return battery; }
    public void setBattery(int battery) { this.battery = Math.max(0, Math.min(100, battery)); }

    public boolean storageInProgress() { return storageInProgress; }
    public void setStorageInProgress(boolean value) { this.storageInProgress = value; }

    public float headingYaw() { return headingYaw; }
    public void setHeadingYaw(float headingYaw) { this.headingYaw = headingYaw; }

    public float visualRoll() { return visualRoll; }
    public void setVisualRoll(float value) { this.visualRoll = value; }

    public float visualPitch() { return visualPitch; }
    public void setVisualPitch(float value) { this.visualPitch = value; }

    public double hoverPhase() { return hoverPhase; }

    public double jumpVelocity() { return jumpVelocity; }
    public void setJumpVelocity(double value) { this.jumpVelocity = value; }

    public boolean jumping() { return jumping; }
    public void setJumping(boolean value) { this.jumping = value; }

    public boolean jumpHeld() { return jumpHeld; }
    public void setJumpHeld(boolean value) { this.jumpHeld = value; }
}
