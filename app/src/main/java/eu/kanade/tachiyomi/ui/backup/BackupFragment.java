package eu.kanade.tachiyomi.ui.backup;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;

import butterknife.ButterKnife;
import butterknife.OnClick;
import eu.kanade.tachiyomi.R;
import eu.kanade.tachiyomi.ui.base.fragment.BaseRxFragment;
import nucleus.factory.RequiresPresenter;

@RequiresPresenter(BackupPresenter.class)
public class BackupFragment extends BaseRxFragment<BackupPresenter> {

    public static BackupFragment newInstance() {
        return new BackupFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        View view = inflater.inflate(R.layout.fragment_backup, container, false);
        ButterKnife.bind(this, view);
        return view;
    }

    @OnClick(R.id.backup_button)
    void onBackupClick() {
        File file = new File(getActivity().getExternalCacheDir(), "backup.json");
        getPresenter().createBackup(file);
    }

    @OnClick(R.id.restore_button)
    void onRestoreClick() {
        File file = new File(getActivity().getExternalCacheDir(), "backup.json");
        getPresenter().restoreBackup(file);
    }

}
