package com.onionsoftware.mk119.commtestsuite.db.bean;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Group {
    private int id;
    private int parent_id;
    private String name;
}
