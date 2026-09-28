package com.example.coursematch.repository;

import com.example.coursematch.entity.OperationLogView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationLogViewRepository extends JpaRepository<OperationLogView, Long> {

    /** 某用户保留的最近视图（用于裁剪历史视图，避免无限累积） */
    @Query("SELECT v.id FROM OperationLogView v "
            + "WHERE v.ownerUserId = :ownerId OR (v.ownerUserId IS NULL AND :ownerId IS NULL) "
            + "ORDER BY v.createdAt DESC, v.id DESC")
    List<Long> findRecentViewIds(@Param("ownerId") Long ownerId, Pageable pageable);

    List<OperationLogView> findByCreatedAtBefore(LocalDateTime cutoff);
}
