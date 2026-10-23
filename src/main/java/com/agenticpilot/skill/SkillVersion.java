package com.agenticpilot.skill;

import com.agenticpilot.governance.ItemVersion;
import com.agenticpilot.user.AppUser;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "skill_version")
public class SkillVersion extends ItemVersion {

    protected SkillVersion() {
    }

    public SkillVersion(UUID skillId, int number, String snapshotJson, AppUser author) {
        super(skillId, number, snapshotJson, author);
    }
}
