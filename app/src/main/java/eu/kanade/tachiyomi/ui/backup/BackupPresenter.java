package eu.kanade.tachiyomi.ui.backup;

import android.os.Bundle;

import java.io.File;

import javax.inject.Inject;

import eu.kanade.tachiyomi.data.backup.BackupManager;
import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class BackupPresenter extends BasePresenter<BackupFragment> {

    @Inject DatabaseHelper db;

    private BackupManager backupManager;
    private File backupFile;
    private File restoreFile;

    private static final int CREATE_BACKUP = 1;
    private static final int RESTORE_BACKUP = 2;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        backupManager = new BackupManager(db);

        restartableLatestCache(CREATE_BACKUP,
                this::getBackupObservable,
                (view, next) -> {});

        restartableLatestCache(RESTORE_BACKUP,
                this::getRestoreObservable,
                (view, next) -> {});
    }

    public void createBackup(File backupFile) {
        if (isUnsubscribed(CREATE_BACKUP)) {
            this.backupFile = backupFile;
            start(CREATE_BACKUP);
        }
    }

    public void restoreBackup(File restoreFile) {
        if (isUnsubscribed(RESTORE_BACKUP)) {
            this.restoreFile = restoreFile;
            start(RESTORE_BACKUP);
        }
    }

    private Observable<Boolean> getBackupObservable() {
        return Observable.fromCallable(() -> {
            backupManager.backupToFile(backupFile);
            return true;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

    private Observable<Boolean> getRestoreObservable() {
        return Observable.fromCallable(() -> {
            backupManager.restoreFromFile(restoreFile);
            return true;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

}
