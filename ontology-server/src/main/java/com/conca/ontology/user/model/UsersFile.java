package com.conca.ontology.user.model;

import java.util.ArrayList;
import java.util.List;

/** data/users.json 文件结构（用户唯一事实源） */
public record UsersFile(int version, List<UserEntry> users) {

    public static UsersFile empty() {
        return new UsersFile(0, new ArrayList<>());
    }
}
