package com.anticheat.detection.physics;

/**
 * MovementInput移动输入记录类，记录玩家的输入状态。
 * 用于物理模拟器预测，保存方向键、跳跃、潜行、疾跑等状态。
 */
public class MovementInput {

    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jumping;
    private boolean sneaking;
    private boolean sprinting;

    public MovementInput() {
        this.forward = false;
        this.backward = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.sneaking = false;
        this.sprinting = false;
    }

    public MovementInput(boolean forward, boolean backward, boolean left, boolean right,
                        boolean jumping, boolean sneaking, boolean sprinting) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jumping = jumping;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
    }

    public boolean isForward() {
        return forward;
    }

    public void setForward(boolean forward) {
        this.forward = forward;
    }

    public boolean isBackward() {
        return backward;
    }

    public void setBackward(boolean backward) {
        this.backward = backward;
    }

    public boolean isLeft() {
        return left;
    }

    public void setLeft(boolean left) {
        this.left = left;
    }

    public boolean isRight() {
        return right;
    }

    public void setRight(boolean right) {
        this.right = right;
    }

    public boolean isJumping() {
        return jumping;
    }

    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    public boolean isMoving() {
        return forward || backward || left || right;
    }

    public int getMovementDirection() {
        int direction = 0;
        if (forward) direction |= 1;
        if (backward) direction |= 2;
        if (left) direction |= 4;
        if (right) direction |= 8;
        return direction;
    }

    @Override
    public String toString() {
        return "MovementInput{" +
                "forward=" + forward +
                ", backward=" + backward +
                ", left=" + left +
                ", right=" + right +
                ", jumping=" + jumping +
                ", sneaking=" + sneaking +
                ", sprinting=" + sprinting +
                '}';
    }
}
