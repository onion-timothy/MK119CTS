package com.onionsoftware.mk119.commtestsuite.db.mapper;

import com.onionsoftware.mk119.commtestsuite.db.bean.Group;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@Mapper
public interface GroupMapper {
    void createScheme();
    List<Group> selectGroups();

    List<Group> selectGroupsByParentId(Integer parent_id);

    void insertGroup(Group group);
    void deleteGroup(Integer id);
    void renameGroup(Group group);
}
