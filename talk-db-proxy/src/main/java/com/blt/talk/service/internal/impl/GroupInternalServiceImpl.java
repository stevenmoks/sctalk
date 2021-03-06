/*
 * Copyright © 2013-2017 BLT, Co., Ltd. All Rights Reserved.
 */

package com.blt.talk.service.internal.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.blt.talk.common.code.proto.IMBaseDefine;
import com.blt.talk.common.constant.DBConstant;
import com.blt.talk.common.util.CommonUtils;
import com.blt.talk.service.internal.GroupInternalService;
import com.blt.talk.service.internal.SessionService;
import com.blt.talk.service.jpa.entity.IMGroup;
import com.blt.talk.service.jpa.entity.IMGroupMember;
import com.blt.talk.service.jpa.repository.IMGroupMemberRepository;
import com.blt.talk.service.jpa.repository.IMGroupRepository;
import com.blt.talk.service.jpa.util.JpaRestrictions;
import com.blt.talk.service.jpa.util.SearchCriteria;
import com.blt.talk.service.redis.RedisKeys;

/**
 * 群相关处理
 * 
 * @author 袁贵
 * @version 1.0
 * @since  1.0
 */
@Service
public class GroupInternalServiceImpl implements GroupInternalService {

    @Autowired
    private IMGroupMemberRepository groupMemberRepository;
    @Autowired
    private IMGroupRepository groupRepository;
    @Autowired
    private SessionService sessionService;

    @Autowired
    private StringRedisTemplate redisTemplate;
    
    /* (non-Javadoc)
     * @see com.blt.talk.service.internal.GroupInternalService#insertNewMember(long, long, java.util.List)
     */
    @Override
    public List<Long> insertNewMember(long userId, long groupId, List<Long> members) {

        // 追加更新群组成员
        int time = CommonUtils.currentTimeSeconds();

        // 查询已有成员
        SearchCriteria<IMGroupMember> groupMemeberCriteria = new SearchCriteria<>();
        groupMemeberCriteria.add(JpaRestrictions.eq("groupId", groupId, false));
        groupMemeberCriteria.add(JpaRestrictions.in("userId", members, false));

        List<IMGroupMember> groupMembers = groupMemberRepository.findAll(groupMemeberCriteria);

        List<Long> userIdForInsert;
        if (groupMembers.isEmpty()) {
            userIdForInsert = members;
        } else {
            userIdForInsert = new ArrayList<>();
            userIdForInsert = members.stream().filter(id -> {
                for (IMGroupMember groupMember : groupMembers) {
                    if (groupMember.getId() == id) {
                        return false;
                    }
                }
                return true;
            }).collect(Collectors.toList());
            groupMembers.forEach(groupMember -> {
                groupMember.setStatus(DBConstant.DELETE_STATUS_OK);
                groupMember.setUpdated(time);
            });
        }

        int incMemberCnt = 0;
        // 追加
        for (Long member: userIdForInsert) {
            IMGroupMember groupMember = new IMGroupMember();
            groupMember.setGroupId(groupId);
            groupMember.setStatus(DBConstant.DELETE_STATUS_OK);
            groupMember.setUserId(member);
            groupMember.setCreated(time);
            groupMember.setUpdated(time);
            groupMembers.add(groupMember);
            
            incMemberCnt++;
        }

        groupMemberRepository.save(groupMembers);

        groupMemeberCriteria = new SearchCriteria<>();
        groupMemeberCriteria.add(JpaRestrictions.eq("groupId", groupId, false));
        groupMemeberCriteria.add(JpaRestrictions.eq("status", DBConstant.DELETE_STATUS_OK, false));
        List<IMGroupMember> allGroupMembers = groupMemberRepository.findAll(groupMemeberCriteria);

        // 更新件数 & 版本
        // 更新IMGroup版本
        IMGroup group = groupRepository.findOne(groupId);
        group.setVersion(group.getVersion() + 1);
        if (incMemberCnt > 0) {
            group.setUserCnt(group.getUserCnt() + incMemberCnt);
        }
        group.setUpdated(CommonUtils.currentTimeSeconds());
        groupRepository.save(group);
        // Redis组
        Map<String, String> memberHash = new HashMap<>();
        
        List<Long> allGroupUsers = new ArrayList<>();
        String key = RedisKeys.concat(RedisKeys.GROUP_INFO, groupId);
        String unreadKey = RedisKeys.concat(RedisKeys.GROUP_UNREAD, groupId);
        HashOperations<String, String, String> groupMemberHash = redisTemplate.opsForHash();
        String msgCount = groupMemberHash.get(key, RedisKeys.COUNT);
        allGroupMembers.forEach(member -> {
            memberHash.put(member.getUserId().toString(), String.valueOf(member.getUpdated()));
            allGroupUsers.add(member.getUserId());
            
            // 更新未读计数
            String userKey = RedisKeys.concat(RedisKeys.USER_INFO, member.getUserId());
            groupMemberHash.put(userKey, unreadKey, msgCount);
        });
        
        groupMemberHash.putAll(key, memberHash);

        // 返回新成员
        return allGroupUsers;
    }

    /* (non-Javadoc)
     * @see com.blt.talk.service.internal.GroupInternalService#removeMember(long, long, java.util.List)
     */
    @Override
    public List<Long> removeMember(long userId, long groupId, List<Long> members) {

        int time = CommonUtils.currentTimeSeconds();

        // 查询已有成员
        SearchCriteria<IMGroupMember> groupMemeberCriteria = new SearchCriteria<>();
        groupMemeberCriteria.add(JpaRestrictions.eq("groupId", groupId, false));
        groupMemeberCriteria.add(JpaRestrictions.in("userId", members, false));

        List<IMGroupMember> groupMembers = groupMemberRepository.findAll(groupMemeberCriteria);

        // 更新为删除状态
        groupMembers.forEach(memeber -> {
            memeber.setStatus(DBConstant.DELETE_STATUS_DELETE);
            memeber.setUpdated(time);
        });
        groupMemberRepository.save(groupMembers);

        groupMemeberCriteria = new SearchCriteria<>();
        groupMemeberCriteria.add(JpaRestrictions.eq("groupId", groupId, false));
        groupMemeberCriteria.add(JpaRestrictions.eq("status", DBConstant.DELETE_STATUS_OK, false));
        List<IMGroupMember> allGroupMembers = groupMemberRepository.findAll(groupMemeberCriteria);

        List<Long> allGroupUsers = new ArrayList<>();
        allGroupMembers.forEach(member -> {
            allGroupUsers.add(member.getUserId());
        });
        
        // 更新版本
        IMGroup group = groupRepository.findOne(groupId);
        group.setVersion(group.getVersion() + 1);
        group.setUpdated(CommonUtils.currentTimeSeconds());
        groupRepository.save(group);
        
        // 从Redis里删除成员
        // 从IMCurrentSession里删除会话
        String key = RedisKeys.concat(RedisKeys.GROUP_INFO, groupId);
        HashOperations<String, String, String> groupMemberHash = redisTemplate.opsForHash();
        // CGroupModel#removeSession(nGroupId, setUserId);
        for (Long member: members) {
            groupMemberHash.delete(key, member.toString());
            long sessionId = sessionService.getSessionId(member, groupId, IMBaseDefine.SessionType.SESSION_TYPE_GROUP.getNumber(), false);
            sessionService.remove(sessionId);
        }
        
        // 返回新成员
        return allGroupUsers;
    }

    @Override
    public boolean isValidate(long groupId) {
        String key = RedisKeys.concat(RedisKeys.GROUP_INFO, groupId);
        return redisTemplate.hasKey(key);
    }

    /* (non-Javadoc)
     * @see com.blt.talk.service.internal.GroupInternalService#isValidate(long, long)
     */
    @Override
    public boolean isValidate(long groupId, long userId) {

        String key = RedisKeys.concat(RedisKeys.GROUP_INFO, groupId);
        if (redisTemplate.hasKey(key)) {
            HashOperations<String, String, String> groupMemberHash = redisTemplate.opsForHash();
            groupMemberHash.hasKey(key, String.valueOf(userId));
        }
        return false;
    }
}
