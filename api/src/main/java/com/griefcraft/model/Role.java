package com.griefcraft.model;

import com.griefcraft.AccessProvider;
import com.griefcraft.Engine;
import com.griefcraft.ProtectionAccess;

public abstract class Role extends AbstractSavable implements AccessProvider {

    /**
     * The Engine instance
     */
    private Engine engine;

    /**
     * The state this role is in
     */
    private State state = State.NEW;

    /**
     * The protection this role is for
     */
    private Protection protection;

    /**
     * The role name for the player to grant access to
     */
    private final String roleName;

    /**
     * The access to grant to players that match this role
     */
    private final ProtectionAccess roleAccess;

    public Role(Engine engine, Protection protection, String roleName, ProtectionAccess roleAccess) {
        super(engine);
        this.engine = engine;
        this.protection = protection;
        this.roleName = roleName;
        this.roleAccess = roleAccess;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": roleName=\"" + roleName + "\" access=" + roleAccess.toString() + " protection=\"" + protection + "\"";
    }

    /**
     * Get the protection that this role is for
     *
     * @return
     */
    public Protection getProtection() {
        return protection;
    }

    /**
     * Get the name of the role this defines, e.g a player's name for a PlayerRole
     *
     * @return
     */
    public String getRoleName() {
        return roleName;
    }

    /**
     * Get the {@link ProtectionAccess} this role can provide
     *
     * @return
     */
    public ProtectionAccess getRoleAccess() {
        return roleAccess;
    }

    /**
     * Get the state this role is in
     *
     * @return
     */
    public State getState() {
        return state;
    }

    /**
     * Change the state this role is in
     *
     * @param state
     */
    public void setState(State state) {
        this.state = state;
    }

    @Override
    public void saveImmediately() {
        // this will update or create the role depending on the current state
        engine.getDatabase().saveOrCreateRole(this);
        state = State.UNMODIFIED;
    }

    @Override
    public boolean isSaveNeeded() {
        return state == State.MODIFIED || state == State.NEW;
    }

    @Override
    public void remove() {
        engine.getDatabase().removeRole(this);
        state = State.REMOVED;
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) {
            return false;
        }

        Role o = (Role) object;
        return getType() == o.getType() && roleName.equals(o.roleName) && roleAccess == o.roleAccess && state == o.state;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash *= 17 + getType();
        hash *= 17 + roleName.hashCode();
        hash *= 17 + roleAccess.hashCode();
        return hash;
    }

    /**
     * Get a unique integer that will be used to represent this role
     *
     * @return
     */
    public abstract int getType();

}