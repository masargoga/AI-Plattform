package com.agenticpilot.governance;

import com.agenticpilot.user.AppUser;
import java.util.UUID;

/** An item with an approval lifecycle: its own fields always equal its newest approved version. */
public interface Governed {

    UUID getId();

    String getName();

    /** The person who may edit it besides Admins. */
    AppUser getResponsible();

    Lifecycle getLifecycle();

    void setLifecycle(Lifecycle lifecycle);

    int getLiveVersion();

    void setLiveVersion(int liveVersion);

    default boolean isUsable() {
        return getLifecycle() == Lifecycle.APPROVED;
    }
}
