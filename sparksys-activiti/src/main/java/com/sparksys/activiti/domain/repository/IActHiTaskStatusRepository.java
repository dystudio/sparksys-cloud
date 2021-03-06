package com.sparksys.activiti.domain.repository;

import com.sparksys.activiti.infrastructure.entity.ActHiTaskStatus;

import java.util.List;

/**
 * description: 历史流程记录 仓储类
 *
 * @author: zhouxinlei
 * @date: 2020-07-23 17:12:59
 */
public interface IActHiTaskStatusRepository {
    /**
     * 查询历史流程记录
     *
     * @param processInstanceId 流程实例id
     * @return List<ActHiTaskStatus>
     */
    List<ActHiTaskStatus> getHiTaskStatus(String processInstanceId);
}
