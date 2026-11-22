package club.beenest.iam.authz.notification;

import club.beenest.iam.common.concurrent.IamDistributedLock;
import club.beenest.iam.persistence.manager.IamNotificationLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知投递记录清理测试:按保留期分批删除,保留期为 0 时不动数据,多实例下只有持锁者执行。
 */
class NotificationLogCleanupJobTest {

    private IamNotificationLogManager manager;
    private IamDistributedLock lock;

    @BeforeEach
    void setUp() {
        manager = Mockito.mock(IamNotificationLogManager.class);
        lock = Mockito.mock(IamDistributedLock.class);
    }

    @Test
    @DisplayName("分批删除直到不足一批,总数累加")
    void deletesInBatches() {
        when(manager.deleteBefore(any(), eq(2000))).thenReturn(2000, 2000, 500);
        NotificationLogCleanupJob job = new NotificationLogCleanupJob(manager, lock, 90, 2000, 50);

        assertEquals(4500, job.cleanupOnce());
        verify(manager, times(3)).deleteBefore(any(), eq(2000));
    }

    @Test
    @DisplayName("保留期为 0:永久保留,不删除")
    void zeroRetentionKeepsEverything() {
        NotificationLogCleanupJob job = new NotificationLogCleanupJob(manager, lock, 0, 2000, 50);

        assertEquals(0, job.cleanupOnce());
        verify(manager, never()).deleteBefore(any(), anyInt());
    }

    @Test
    @DisplayName("单轮删除批数有上限,避免长事务")
    void stopsAtBatchLimit() {
        when(manager.deleteBefore(any(), anyInt())).thenReturn(2000);
        NotificationLogCleanupJob job = new NotificationLogCleanupJob(manager, lock, 30, 2000, 3);

        assertEquals(6000, job.cleanupOnce());
        verify(manager, times(3)).deleteBefore(any(), anyInt());
    }

    @Test
    @DisplayName("锁被其它实例持有:本次不删除")
    void skipsWhenLockHeld() {
        when(lock.tryLock(any(), any(Duration.class))).thenReturn(null);
        NotificationLogCleanupJob job = new NotificationLogCleanupJob(manager, lock, 90, 2000, 50);

        job.cleanup();
        verify(manager, never()).deleteBefore(any(), anyInt());
    }

    @Test
    @DisplayName("持锁执行后释放锁")
    void releasesLock() {
        when(lock.tryLock(any(), any(Duration.class))).thenReturn("token");
        when(manager.deleteBefore(any(), anyInt())).thenReturn(10);
        NotificationLogCleanupJob job = new NotificationLogCleanupJob(manager, lock, 90, 2000, 50);

        job.cleanup();
        verify(manager).deleteBefore(any(), eq(2000));
        verify(lock).unlock(any(), eq("token"));
    }
}
