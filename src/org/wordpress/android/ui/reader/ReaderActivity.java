package org.wordpress.android.ui.reader;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.MenuItem;

import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.datasets.ReaderDatabase;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.ui.WPActionBarActivity;
import org.wordpress.android.ui.prefs.UserPrefs;
import org.wordpress.android.ui.reader.ReaderPostListFragment.OnPostSelectedListener;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResult;
import org.wordpress.android.ui.reader.actions.ReaderAuthActions;
import org.wordpress.android.ui.reader.actions.ReaderBlogActions;
import org.wordpress.android.ui.reader.actions.ReaderTagActions;
import org.wordpress.android.ui.reader.actions.ReaderUserActions;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.SysUtils;

/*
 * created by nbradbury
 * this activity serves as the host for ReaderPostListFragment and ReaderPostDetailFragment
 */

public class ReaderActivity extends WPActionBarActivity
                            implements OnPostSelectedListener,
                                       FragmentManager.OnBackStackChangedListener,
                                       ReaderFullScreenUtils.FullScreenListener {

    public static enum ReaderFragmentType { POST_LIST, POST_DETAIL, UNKNOWN }

    public static final String ARG_READER_FRAGMENT = "reader_fragment";

    private static boolean mHasPerformedInitialUpdate = false;
    private static boolean mHasPerformedPurge = false;
    private boolean mIsFullScreen = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (isFullScreenSupported())
            ReaderFullScreenUtils.enableActionBarOverlay(this);

        super.onCreate(savedInstanceState);
        createMenuDrawer(R.layout.reader_activity_main);

        if (savedInstanceState == null) {
            // determine which fragment to show, default to post list
            final ReaderFragmentType fragmentType;
            if (getIntent().hasExtra(ARG_READER_FRAGMENT)) {
                fragmentType = (ReaderFragmentType) getIntent().getSerializableExtra(ARG_READER_FRAGMENT);
            } else {
                fragmentType = ReaderFragmentType.POST_LIST;
            }
            switch (fragmentType) {
                case POST_LIST:
                    String tagName = getIntent().getStringExtra(ReaderPostListFragment.KEY_TAG_NAME);
                    if (TextUtils.isEmpty(tagName))
                        tagName = UserPrefs.getReaderTag();
                    if (TextUtils.isEmpty(tagName) || !ReaderTagTable.tagExists(tagName))
                        tagName = ReaderTag.TAG_NAME_DEFAULT;
                    showListFragment(tagName);
                    break;
                case POST_DETAIL:
                    long blogId = getIntent().getLongExtra(ReaderPostDetailFragment.ARG_BLOG_ID, 0);
                    long postId = getIntent().getLongExtra(ReaderPostDetailFragment.ARG_POST_ID, 0);
                    showDetailFragment(blogId, postId);
                    break;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, new IntentFilter(ACTION_REFRESH_POSTS));
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();
        checkMenuDrawer();
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // purge the database of older data at startup
        if (!mHasPerformedPurge) {
            mHasPerformedPurge = true;
            ReaderDatabase.purgeAsync();
        }

        if (!mHasPerformedInitialUpdate)
            performInitialUpdate();
    }

    @Override
    public void onBackStackChanged() {
        checkMenuDrawer();
        // return from full-screen when backstack changes
        if (isFullScreen())
            onRequestFullScreen(false);
    }

    @Override
    public void onBackPressed() {
        if (mMenuDrawer != null && mMenuDrawer.isMenuVisible()) {
            super.onBackPressed();
        } else if (hasDetailFragment()) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home && hasDetailFragment()) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkMenuDrawer() {
        if (mMenuDrawer == null) {
            AppLog.w(T.READER, "null menu drawer");
            return;
        }
        mMenuDrawer.setDrawerIndicatorEnabled(!hasDetailFragment());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        boolean isResultOK = (resultCode==Activity.RESULT_OK);
        final ReaderPostListFragment listFragment = getListFragment();
        final ReaderPostDetailFragment detailFragment = getDetailFragment();

        switch (requestCode) {
            // user just returned from the tag editor
            case Constants.INTENT_READER_TAGS :
                if (isResultOK && listFragment != null && data != null) {
                    // reload tags if they were changed, and set the last tag added as the current one
                    if (data.getBooleanExtra(ReaderTagActivity.KEY_TAGS_CHANGED, false)) {
                        listFragment.reloadTags();
                        String lastAddedTag = data.getStringExtra(ReaderTagActivity.KEY_LAST_ADDED_TAG);
                        if (!TextUtils.isEmpty(lastAddedTag))
                            listFragment.setCurrentTag(lastAddedTag);
                    }
                }
                break;

            // user just returned from post detail, reload the displayed post if it changed (will
            // only be RESULT_OK if changed)
            // TODO: no longer works now that detail is a fragment
            case Constants.INTENT_READER_POST_DETAIL:
                if (isResultOK && listFragment != null && data!=null) {
                    long blogId = data.getLongExtra(ReaderPostDetailFragment.ARG_BLOG_ID, 0);
                    long postId = data.getLongExtra(ReaderPostDetailFragment.ARG_POST_ID, 0);
                    boolean isBlogFollowStatusChanged = data.getBooleanExtra(ReaderPostDetailFragment.ARG_BLOG_FOLLOW_STATUS_CHANGED, false);
                    ReaderPost updatedPost = ReaderPostTable.getPost(blogId, postId);
                    if (updatedPost != null) {
                        listFragment.reloadPost(updatedPost);
                        // update 'following' status on all other posts in the same blog.
                        if (isBlogFollowStatusChanged) {
                            listFragment.updateFollowStatusOnPostsForBlog(blogId, updatedPost.isFollowedByCurrentUser);
                        }
                    }
                }
                break;

            // user just returned from reblogging activity, reload the displayed post if reblogging
            // succeeded
            case Constants.INTENT_READER_REBLOG:
                if (isResultOK && data!=null) {
                    long blogId = data.getLongExtra(ReaderReblogActivity.ARG_BLOG_ID, 0);
                    long postId = data.getLongExtra(ReaderReblogActivity.ARG_POST_ID, 0);
                    if (listFragment != null)
                        listFragment.reloadPost(ReaderPostTable.getPost(blogId, postId));
                    if (detailFragment != null)
                        detailFragment.reloadPost();
                }
                break;
        }
    }

    @Override
    public void onSignout() {
        super.onSignout();
        mHasPerformedInitialUpdate = false;

        // reader database will have been cleared by the time this is called, but the fragments must
        // be removed or else they will continue to show the same articles - onResume() will take care
        // of re-displaying the correct fragment if necessary
        removeFragments();
    }

    /*
     * remove both the list & detail fragments
     */
    private void removeFragments() {
        Fragment listFragment = getListFragment();
        Fragment detailFragment = getDetailFragment();
        if (listFragment == null && detailFragment == null)
            return;

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        if (detailFragment != null)
            ft.remove(detailFragment);
        if (listFragment != null)
            ft.remove(listFragment);

        ft.commit();
    }

    /*
     * show fragment containing list of latest posts for a specific tag
     */
    private void showListFragment(final String tagName) {
        String tagForFragment = ReaderFragmentType.POST_LIST.toString();
        Fragment fragment = ReaderPostListFragment.newInstance(tagName);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, tagForFragment)
                .commit();
    }

    private ReaderPostListFragment getListFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(ReaderFragmentType.POST_LIST.toString());
        if (fragment == null)
            return null;
        return ((ReaderPostListFragment) fragment);
    }

    /*
     * show fragment containing detail for passed post
     */
    private void showDetailFragment(long blogId, long postId) {
        String tagForFragment = ReaderFragmentType.POST_DETAIL.toString();
        Fragment fragment = ReaderPostDetailFragment.newInstance(blogId, postId);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, tagForFragment)
                .setTransitionStyle(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .addToBackStack(tagForFragment)
                .commit();
    }

    private ReaderPostDetailFragment getDetailFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(ReaderFragmentType.POST_DETAIL.toString());
        if (fragment == null)
            return null;
        return ((ReaderPostDetailFragment) fragment);
    }

    private boolean hasDetailFragment() {
        return (getDetailFragment() != null);
    }

    /*
     * initial update performed at startup to ensure we have the latest reader-related info
     */
    private void performInitialUpdate() {
        if (!NetworkUtils.isNetworkAvailable(this))
            return;

        mHasPerformedInitialUpdate = true;

        // animate refresh button in post list if tags are being updated for the first time
        ReaderPostListFragment listFragment = getListFragment();
        final boolean animateRefresh = (listFragment != null && ReaderTagTable.isEmpty());
        if (animateRefresh)
            listFragment.animateRefreshButton(true);

        // request the list of tags first and don't perform other calls until it returns - this
        // way changes to tags can be shown as quickly as possible (esp. important when tags
        // don't already exist)
        ReaderActions.UpdateResultListener listener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(UpdateResult result) {
                ReaderPostListFragment listFragment = getListFragment();
                if (listFragment != null) {
                    if (animateRefresh)
                        listFragment.animateRefreshButton(false);
                    if (result == UpdateResult.CHANGED)
                        listFragment.refreshTags();
                }

                // now that tags have been retrieved, perform the other requests - first update
                // the current user to ensure we have their user_id as well as their latest info
                // in case they changed their avatar, name, etc. since last time
                AppLog.i(T.READER, "updating current user");
                ReaderUserActions.updateCurrentUser(null);

                // update followed blogs
                AppLog.i(T.READER, "updating followed blogs");
                ReaderBlogActions.updateFollowedBlogs();

                // update cookies so that we can show authenticated images in WebViews
                AppLog.i(T.READER, "updating cookies");
                ReaderAuthActions.updateCookies(ReaderActivity.this);
            }
        };
        ReaderTagActions.updateTags(listener);
    }

    /*
     * user tapped a post in the post list fragment
     */
    @Override
    public void onPostSelected(long blogId, long postId) {
        showDetailFragment(blogId, postId);
    }

    /*
     * post detail is requesting fullscreen mode
     */
    @Override
    public boolean onRequestFullScreen(boolean enableFullScreen) {
        if (!isFullScreenSupported() || enableFullScreen == mIsFullScreen)
            return false;

        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null)
            return false;

        if (enableFullScreen) {
            actionBar.hide();
        } else {
            actionBar.show();
        }

        mIsFullScreen = enableFullScreen;
        return true;
    }

    @Override
    public boolean isFullScreen() {
        return mIsFullScreen;
    }

    /*
     * auto-hiding the ActionBar is jittery/buggy on Gingerbread (and probably other
     * pre-ICS devices), and requires the ActionBar overlay (ICS or later)
     */
    public boolean isFullScreenSupported() {
        return (SysUtils.isGteAndroid4());
    }

    /*
     * this broadcast receiver handles the ACTION_REFRESH_POSTS action, which may be called from the
     * post list fragment if the device is rotated while an update is in progress
     */
    protected static final String ACTION_REFRESH_POSTS = "action_refresh_posts";
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_REFRESH_POSTS.equals(intent.getAction())) {
                AppLog.i(T.READER, "received ACTION_REFRESH_POSTS");
                ReaderPostListFragment listFragment = getListFragment();
                if (listFragment != null)
                    listFragment.refreshPosts();
            }
        }
    };

}
