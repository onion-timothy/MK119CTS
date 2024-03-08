package com.onionsoftware.mk119.commtestsuite.db.service;

import com.onionsoftware.mk119.commtestsuite.db.bean.Group;
import com.onionsoftware.mk119.commtestsuite.db.mapper.DeviceMapper;
import com.onionsoftware.mk119.commtestsuite.db.mapper.GroupMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GroupService {
    private final GroupMapper groupMapper;

    public GroupService(GroupMapper groupMapper){
        this.groupMapper = groupMapper;
    }

    public void createScheme() {
        groupMapper.createScheme();
    }

    public List<Group> selectGroups(){
        return groupMapper.selectGroups();
    }

    public List<Group> selectGroupsByParentGroupId(Integer parent_id){
        return groupMapper.selectGroupsByParentId(parent_id);
    }

    public void insertGroup(Group group){
        groupMapper.insertGroup(group);
    }

    public void deleteGroup(Integer id){
        groupMapper.deleteGroup(id);
    }

    public void renameGroup(Group group){
        groupMapper.renameGroup(group);
    }
}
