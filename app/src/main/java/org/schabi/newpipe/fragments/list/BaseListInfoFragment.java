package org.schabi.newpipe.fragments.list;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ListInfo;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.views.NewPipeRecyclerView;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import org.schabi.newpipe.download.BulkDownloadDialog;
import org.schabi.newpipe.util.OnClickGesture;
import us.shandian.giga.get.PendingFetchMission;
import us.shandian.giga.service.DownloadManagerService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public abstract class BaseListInfoFragment<I extends InfoItem, L extends ListInfo<I>>
        extends BaseListFragment<L, ListExtractor.InfoItemsPage<I>>
        implements BulkDownloadDialog.Listener {
    protected int serviceId = Constants.NO_SERVICE_ID;
    protected String name;
    protected String url;

    private final UserAction errorUserAction;
    protected L currentInfo;
    protected Page currentNextPage;
    protected Disposable currentWorker;
    protected boolean filterFutureItems;

    // -----------------------------------------------------------------------
    // Multi-select / bulk-download state
    // -----------------------------------------------------------------------
    protected boolean isMultiSelectMode = false;
    protected final java.util.LinkedHashSet<StreamInfoItem> selectedItems = new java.util.LinkedHashSet<>();
    protected MenuItem menuSelectVideos;
    protected MenuItem menuSelectAll;
    protected MenuItem menuDownloadSelected;
    protected MenuItem menuCancelSelect;
    protected OnBackPressedCallback backPressedCallback;

    protected BaseListInfoFragment(final UserAction errorUserAction) {
        this.errorUserAction = errorUserAction;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("serviceId", serviceId);
        outState.putString("name", name);
        outState.putString("url", url);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        serviceId = savedInstanceState.getInt("serviceId", Constants.NO_SERVICE_ID);
        name = savedInstanceState.getString("name");
        url = savedInstanceState.getString("url");
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        setTitle(name);
        showListFooter(hasMoreItems());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filterFutureItems = PreferenceManager.getDefaultSharedPreferences(getActivity())
                .getBoolean(getString(R.string.filter_future_items_key), true);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (currentWorker != null) {
            currentWorker.dispose();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Check if it was loading when the fragment was stopped/paused,
        if (wasLoading.getAndSet(false)) {
            if (hasMoreItems() && !infoListAdapter.getItemsList().isEmpty()) {
                loadMoreItems();
            } else {
                doInitialLoadLogic();
            }
        }
    }

    // Multi-select / bulk-download state
    protected boolean isMultiSelectMode = false;
    protected final java.util.LinkedHashSet<StreamInfoItem> selectedItems = new java.util.LinkedHashSet<>();
    protected MenuItem menuSelectVideos;
    protected MenuItem menuSelectAll;
    protected MenuItem menuDownloadSelected;
    protected MenuItem menuCancelSelect;
    protected OnBackPressedCallback backPressedCallback;

    @Override
    protected void initListeners() {
        super.initListeners();
        backPressedCallback = new OnBackPressedCallback(false /*initially disabled*/) {
            @Override
            public void handleOnBackPressed() {
                exitMultiSelectMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backPressedCallback);
    }

    protected void setupMultiSelectMenu(@NonNull final Menu menu) {
        menuSelectVideos = menu.findItem(R.id.menu_item_select_videos);
        menuSelectAll = menu.findItem(R.id.menu_item_select_all);
        menuDownloadSelected = menu.findItem(R.id.menu_item_download_selected);
        menuCancelSelect = menu.findItem(R.id.menu_item_cancel_select);
    }

    protected void prepareMultiSelectMenu(@NonNull final Menu menu) {
        if (menuSelectVideos == null) {
            return;
        }
        if (isMultiSelectMode) {
            menuSelectVideos.setVisible(false);
            menuSelectAll.setVisible(true);
            menuDownloadSelected.setVisible(true);
            menuDownloadSelected.setEnabled(!selectedItems.isEmpty());
            final long streamCount = infoListAdapter.getItemsList().stream()
                    .filter(i -> i instanceof StreamInfoItem).count();
            menuSelectAll.setTitle(
                    selectedItems.size() == streamCount
                            ? getString(R.string.deselect_all)
                            : getString(R.string.select_all));
            menuCancelSelect.setVisible(true);
        } else {
            final boolean hasItems = infoListAdapter != null
                    && infoListAdapter.getItemsList().stream()
                            .anyMatch(i -> i instanceof StreamInfoItem);
            menuSelectVideos.setVisible(hasItems);
            menuSelectAll.setVisible(false);
            menuDownloadSelected.setVisible(false);
            menuCancelSelect.setVisible(false);
        }
    }

    protected boolean handleMultiSelectMenuSelection(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_item_select_videos) {
            enterMultiSelectMode();
            return true;
        } else if (id == R.id.menu_item_select_all) {
            toggleSelectAll();
            return true;
        } else if (id == R.id.menu_item_download_selected) {
            if (!selectedItems.isEmpty()) {
                openBulkDownloadDialog();
            }
            return true;
        } else if (id == R.id.menu_item_cancel_select) {
            exitMultiSelectMode();
            return true;
        }
        return false;
    }

    protected void enterMultiSelectMode() {
        if (isMultiSelectMode) {
            return;
        }
        isMultiSelectMode = true;
        selectedItems.clear();

        infoListAdapter.setOnStreamSelectedListener(new OnClickGesture<StreamInfoItem>() {
            @Override
            public void selected(final StreamInfoItem selectedItem) {
                if (selectedItems.contains(selectedItem)) {
                    selectedItems.remove(selectedItem);
                } else {
                    selectedItems.add(selectedItem);
                }
                infoListAdapter.notifyDataSetChanged();
                updateMultiSelectTitle();
                if (activity != null) {
                    activity.invalidateOptionsMenu();
                }
            }
        });

        infoListAdapter.setSelectionStateProvider(
                item -> item instanceof StreamInfoItem
                        && selectedItems.contains((StreamInfoItem) item));

        updateMultiSelectTitle();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(true);
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    protected void exitMultiSelectMode() {
        if (!isMultiSelectMode) {
            return;
        }
        isMultiSelectMode = false;
        selectedItems.clear();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(false);
        }

        initListeners();
        infoListAdapter.setSelectionStateProvider(null);

        final ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getName());
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    protected void toggleSelectAll() {
        final List<InfoItem> items = infoListAdapter.getItemsList();
        final long streamCount = items.stream()
                .filter(i -> i instanceof StreamInfoItem).count();
        if (selectedItems.size() == streamCount) {
            selectedItems.clear();
        } else {
            for (final InfoItem i : items) {
                if (i instanceof StreamInfoItem) {
                    selectedItems.add((StreamInfoItem) i);
                }
            }
        }
        infoListAdapter.notifyDataSetChanged();
        updateMultiSelectTitle();
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    protected void updateMultiSelectTitle() {
        final ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            final int count = selectedItems.size();
            final String title = getResources().getQuantityString(
                    R.plurals.feed_group_dialog_selection_count, count, count);
            actionBar.setTitle(title);
        }
    }

    protected void openBulkDownloadDialog() {
        BulkDownloadDialog.newInstance(selectedItems.size())
                .show(getChildFragmentManager(), "BULK_DOWNLOAD");
    }

    @Override
    public void onBulkDownloadConfirmed(final boolean audioOnly,
                                        @NonNull final String qualityLabel,
                                        @NonNull final BulkDownloadDialog.ExistingFileBehavior behavior,
                                        final int fetchThreads) {
        final List<StreamInfoItem> itemsToDownload = new ArrayList<>(selectedItems);
        exitMultiSelectMode();
        final int fileBehavior;
        switch (behavior) {
            case OVERWRITE: fileBehavior = PendingFetchMission.BEHAVIOR_OVERWRITE; break;
            case UNIQUE_NAME: fileBehavior = PendingFetchMission.BEHAVIOR_UNIQUE_NAME; break;
            default: fileBehavior = PendingFetchMission.BEHAVIOR_SKIP; break;
        }
        PendingFetchMission.setMaxConcurrentFetches(fetchThreads);
        final android.content.Context ctx = requireContext().getApplicationContext();
        for (final StreamInfoItem item : itemsToDownload) {
            DownloadManagerService.addPendingFetchMission(ctx,
                    item.getServiceId(), item.getUrl(), item.getName(),
                    audioOnly, qualityLabel, fileBehavior);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (currentWorker != null) {
            currentWorker.dispose();
            currentWorker = null;
        }
        if (infoListAdapter != null) {
            infoListAdapter.setSelectionStateProvider(null);
        }
        isMultiSelectMode = false;
        selectedItems.clear();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void writeTo(final Queue<Object> objectsToSave) {
        super.writeTo(objectsToSave);
        objectsToSave.add(currentInfo);
        objectsToSave.add(currentNextPage);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        super.readFrom(savedObjects);
        currentInfo = (L) savedObjects.poll();
        currentNextPage = (Page) savedObjects.poll();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void doInitialLoadLogic() {
        if (DEBUG) {
            Log.d(TAG, "doInitialLoadLogic() called");
        }
        if (currentInfo == null) {
            startLoading(false);
        } else {
            handleResult(currentInfo);
        }
    }

    /**
     * Implement the logic to load the info from the network.<br/>
     * You can use the default implementations from {@link org.schabi.newpipe.util.ExtractorHelper}.
     *
     * @param forceLoad allow or disallow the result to come from the cache
     * @return Rx {@link Single} containing the {@link ListInfo}
     */
    protected abstract Single<L> loadResult(boolean forceLoad);

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);

        showListFooter(false);
        infoListAdapter.clearStreamItemList();

        currentInfo = null;
        if (currentWorker != null) {
            currentWorker.dispose();
        }
        currentWorker = loadResult(forceLoad)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((@NonNull L result) -> {
                    isLoading.set(false);
                    currentInfo = result;
                    currentNextPage = result.getNextPage();
                    handleResult(result);
                }, throwable ->
                        showError(new ErrorInfo(throwable, errorUserAction,
                                "Start loading: " + url, serviceId)));
    }

    /**
     * Implement the logic to load more items.
     * <p>You can use the default implementations
     * from {@link org.schabi.newpipe.util.ExtractorHelper}.</p>
     *
     * @return Rx {@link Single} containing the {@link ListExtractor.InfoItemsPage}
     */
    protected abstract Single<ListExtractor.InfoItemsPage<I>> loadMoreItemsLogic();

    @Override
    protected void loadMoreItems() {
        isLoading.set(true);

        if (currentWorker != null) {
            currentWorker.dispose();
        }

        forbidDownwardFocusScroll();

        currentWorker = loadMoreItemsLogic()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(this::allowDownwardFocusScroll)
                .subscribe(infoItemsPage -> {
                    isLoading.set(false);
                    handleNextItems(infoItemsPage);
                }, (@NonNull Throwable throwable) ->
                        dynamicallyShowErrorPanelOrSnackbar(new ErrorInfo(throwable,
                                errorUserAction, "Loading more items: " + url, serviceId)));
    }

    private void forbidDownwardFocusScroll() {
        if (itemsList instanceof NewPipeRecyclerView) {
            ((NewPipeRecyclerView) itemsList).setFocusScrollAllowed(false);
        }
    }

    private void allowDownwardFocusScroll() {
        if (itemsList instanceof NewPipeRecyclerView) {
            ((NewPipeRecyclerView) itemsList).setFocusScrollAllowed(true);
        }
    }

    @Override
    public void handleNextItems(final ListExtractor.InfoItemsPage<I> result) {
        super.handleNextItems(result);

        currentNextPage = result.getNextPage();
        infoListAdapter.addInfoItemList(result.getItems());

        showListFooter(hasMoreItems());

        if (!result.getErrors().isEmpty()) {
            dynamicallyShowErrorPanelOrSnackbar(new ErrorInfo(result.getErrors(), errorUserAction,
                    "Get next items of: " + url, serviceId));
        }
    }

    @Override
    protected boolean hasMoreItems() {
        return Page.isValid(currentNextPage);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void handleResult(@NonNull final L result) {
        super.handleResult(result);

        name = result.getName();
        setTitle(name);

        if (infoListAdapter.getItemsList().isEmpty()) {
            if (!result.getRelatedItems().isEmpty()) {
                infoListAdapter.addInfoItemList(result.getRelatedItems().stream()
                        .filter(item -> !filterFutureItems || !(item instanceof StreamInfoItem) || ((StreamInfoItem) item).getUploadDate() == null
                                || ((StreamInfoItem)item).getUploadDate().offsetDateTime().isBefore(OffsetDateTime.now()))
                                .collect(Collectors.toList()));
                showListFooter(hasMoreItems());
            } else {
                infoListAdapter.clearStreamItemList();
                // showEmptyState should be called only if there is no item as
                // well as no header in infoListAdapter
                if (!(result instanceof ChannelInfo && infoListAdapter.getItemCount() == 1)) {
                    showEmptyState();
                }
            }
        }

        if (!result.getErrors().isEmpty()) {
            final List<Throwable> errors = new ArrayList<>(result.getErrors());
            // handling ContentNotSupportedException not to show the error but an appropriate string
            // so that crashes won't be sent uselessly and the user will understand what happened
            errors.removeIf(ContentNotSupportedException.class::isInstance);

            if (!errors.isEmpty()) {
                dynamicallyShowErrorPanelOrSnackbar(new ErrorInfo(result.getErrors(),
                        errorUserAction, "Start loading: " + url, serviceId));
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    protected void setInitialData(final int sid, final String u, final String title) {
        this.serviceId = sid;
        this.url = u;
        this.name = !TextUtils.isEmpty(title) ? title : "";
    }

    private void dynamicallyShowErrorPanelOrSnackbar(final ErrorInfo errorInfo) {
        if (infoListAdapter.getItemCount() == 0) {
            // show error panel only if no items already visible
            showError(errorInfo);
        } else {
            isLoading.set(false);
            showSnackBarError(errorInfo);
        }
    }

    public int getServiceId() {
        return serviceId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }
    @Override
    protected void initListeners() {
        super.initListeners();
        backPressedCallback = new OnBackPressedCallback(false /*initially disabled*/) {
            @Override
            public void handleOnBackPressed() {
                exitMultiSelectMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backPressedCallback);
    }

    protected void setupMultiSelectMenu(@NonNull final Menu menu) {
        menuSelectVideos = menu.findItem(R.id.menu_item_select_videos);
        menuSelectAll = menu.findItem(R.id.menu_item_select_all);
        menuDownloadSelected = menu.findItem(R.id.menu_item_download_selected);
        menuCancelSelect = menu.findItem(R.id.menu_item_cancel_select);
    }

    protected void prepareMultiSelectMenu(@NonNull final Menu menu) {
        if (menuSelectVideos == null) {
            return;
        }
        if (isMultiSelectMode) {
            menuSelectVideos.setVisible(false);
            menuSelectAll.setVisible(true);
            menuDownloadSelected.setVisible(true);
            menuDownloadSelected.setEnabled(!selectedItems.isEmpty());
            final long streamCount = infoListAdapter.getItemsList().stream()
                    .filter(i -> i instanceof StreamInfoItem).count();
            menuSelectAll.setTitle(
                    selectedItems.size() == streamCount
                            ? getString(R.string.deselect_all)
                            : getString(R.string.select_all));
            menuCancelSelect.setVisible(true);
        } else {
            final boolean hasItems = infoListAdapter != null
                    && infoListAdapter.getItemsList().stream()
                            .anyMatch(i -> i instanceof StreamInfoItem);
            menuSelectVideos.setVisible(hasItems);
            menuSelectAll.setVisible(false);
            menuDownloadSelected.setVisible(false);
            menuCancelSelect.setVisible(false);
        }
    }

    protected boolean handleMultiSelectMenuSelection(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_item_select_videos) {
            enterMultiSelectMode();
            return true;
        } else if (id == R.id.menu_item_select_all) {
            toggleSelectAll();
            return true;
        } else if (id == R.id.menu_item_download_selected) {
            if (!selectedItems.isEmpty()) {
                openBulkDownloadDialog();
            }
            return true;
        } else if (id == R.id.menu_item_cancel_select) {
            exitMultiSelectMode();
            return true;
        }
        return false;
    }

    protected void enterMultiSelectMode() {
        if (isMultiSelectMode) {
            return;
        }
        isMultiSelectMode = true;
        selectedItems.clear();

        infoListAdapter.setOnStreamSelectedListener(new OnClickGesture<StreamInfoItem>() {
            @Override
            public void selected(final StreamInfoItem selectedItem) {
                if (selectedItems.contains(selectedItem)) {
                    selectedItems.remove(selectedItem);
                } else {
                    selectedItems.add(selectedItem);
                }
                infoListAdapter.notifyDataSetChanged();
                updateMultiSelectTitle();
                if (activity != null) {
                    activity.invalidateOptionsMenu();
                }
            }
        });

        infoListAdapter.setSelectionStateProvider(
                item -> item instanceof StreamInfoItem
                        && selectedItems.contains((StreamInfoItem) item));

        updateMultiSelectTitle();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(true);
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    protected void exitMultiSelectMode() {
        if (!isMultiSelectMode) {
            return;
        }
        isMultiSelectMode = false;
        selectedItems.clear();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(false);
        }

        initListeners();
        infoListAdapter.setSelectionStateProvider(null);

        final ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getName());
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    protected void toggleSelectAll() {
        final List<InfoItem> items = infoListAdapter.getItemsList();
        final long streamCount = items.stream()
                .filter(i -> i instanceof StreamInfoItem).count();
        if (selectedItems.size() == streamCount) {
            selectedItems.clear();
        } else {
            for (final InfoItem i : items) {
                if (i instanceof StreamInfoItem) {
                    selectedItems.add((StreamInfoItem) i);
                }
            }
        }
        infoListAdapter.notifyDataSetChanged();
        updateMultiSelectTitle();
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    protected void updateMultiSelectTitle() {
        final ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            final int count = selectedItems.size();
            final String title = getResources().getQuantityString(
                    R.plurals.feed_group_dialog_selection_count, count, count);
            actionBar.setTitle(title);
        }
    }

    protected void openBulkDownloadDialog() {
        BulkDownloadDialog.newInstance(selectedItems.size())
                .show(getChildFragmentManager(), "BULK_DOWNLOAD");
    }

    @Override
    public void onBulkDownloadConfirmed(final boolean audioOnly,
                                        @NonNull final String qualityLabel,
                                        @NonNull final BulkDownloadDialog.ExistingFileBehavior behavior,
                                        final int fetchThreads) {
        final List<StreamInfoItem> itemsToDownload = new ArrayList<>(selectedItems);
        exitMultiSelectMode();
        final int fileBehavior;
        switch (behavior) {
            case OVERWRITE: fileBehavior = PendingFetchMission.BEHAVIOR_OVERWRITE; break;
            case UNIQUE_NAME: fileBehavior = PendingFetchMission.BEHAVIOR_UNIQUE_NAME; break;
            default: fileBehavior = PendingFetchMission.BEHAVIOR_SKIP; break;
        }
        PendingFetchMission.setMaxConcurrentFetches(fetchThreads);
        final android.content.Context ctx = requireContext().getApplicationContext();
        for (final StreamInfoItem item : itemsToDownload) {
            DownloadManagerService.addPendingFetchMission(ctx,
                    item.getServiceId(), item.getUrl(), item.getName(),
                    audioOnly, qualityLabel, fileBehavior);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (currentWorker != null) {
            currentWorker.dispose();
            currentWorker = null;
        }
        if (infoListAdapter != null) {
            infoListAdapter.setSelectionStateProvider(null);
        }
        isMultiSelectMode = false;
    }
}
