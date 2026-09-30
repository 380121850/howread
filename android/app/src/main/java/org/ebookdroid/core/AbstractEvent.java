package org.ebookdroid.core;

import org.ebookdroid.common.bitmaps.BitmapManager;
import org.ebookdroid.common.bitmaps.Bitmaps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class AbstractEvent implements IEvent {

    protected final List<PageTreeNode> nodesToDecode = new ArrayList<PageTreeNode>();
    protected final List<Bitmaps> bitmapsToRecycle = new ArrayList<Bitmaps>();

    public AbstractViewController ctrl;
    public ViewState viewState;

    protected AbstractEvent() {
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.IEvent#process()
     */
    @Override
    public ViewState process() {
        viewState = calculatePageVisibility(viewState);

        ctrl.firstVisiblePage = viewState.pages.firstVisible;
        ctrl.lastVisiblePage = viewState.pages.lastVisible;

        final Page[] pages = ctrl.model.getPages();
        for (final Page page : pages) {
            if (page.index.viewIndex == pages.length-1) {
                page.isLastPage = true;
            } else {
                page.isLastPage = false;
            }
            process(page);
        }

        BitmapManager.release(bitmapsToRecycle);

        if (!nodesToDecode.isEmpty()) {
            decodePageTreeNodes(viewState, nodesToDecode);
        }

        return viewState;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.IEvent#process(org.ebookdroid.core.ViewState, org.ebookdroid.core.Page)
     */
    @Override
    public final boolean process(final Page page) {
        if (page.recycled) {
            return false;
        }
        if (viewState.isPageKeptInMemory(page) || viewState.isPageVisible(page)) {
            return process(page.nodes);
        }

        // A page with a decode already queued/in flight (its nodes were
        // requested by a previous event) must not be recycled here: right
        // after a progressive open pages have no real bounds yet, and this
        // cancel left the visible page blank until some later event.
        if (page.nodes != null && page.nodes.root != null && page.nodes.root.decodingNow.get()) {
            return false;
        }

        recyclePage(viewState, page);
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.IEvent#process(org.ebookdroid.core.ViewState, org.ebookdroid.core.PageTree,
     * org.ebookdroid.core.PageTreeLevel)
     */
    @Override
    public boolean process(final PageTree nodes, final PageTreeLevel level) {
        return nodes.process(this, level, true);
    }

    protected ViewState calculatePageVisibility(final ViewState initial) {
        int firstVisiblePage = -1;
        int lastVisiblePage = -1;
        for (final Page page : ctrl.model.getPages()) {
            if (ctrl.isPageVisible(page, initial)) {
                if (firstVisiblePage == -1) {
                    firstVisiblePage = page.index.viewIndex;
                }
                lastVisiblePage = page.index.viewIndex;
            } else if (firstVisiblePage != -1) {
                break;
            }
        }
        return new ViewState(initial, firstVisiblePage, lastVisiblePage);
    }

    protected final void decodePageTreeNodes(final ViewState viewState, final List<PageTreeNode> nodesToDecode) {
        android.util.Log.i("BENCH", "remap nodes=" + nodesToDecode.size() + " fp=" + viewState.pages.firstVisible);
        final PageTreeNode best = Collections.min(nodesToDecode, new PageTreeNodeComparator(viewState));
        final DecodeService ds = ctrl.getBase().getDecodeService();

        if (ds != null) {
            ds.decodePage(viewState, best);

            for (final PageTreeNode node : nodesToDecode) {
                if (node != best) {
                    ds.decodePage(viewState, node);
                }
            }
        }
    }

    protected final void recyclePage(final ViewState viewState, final Page page) {
        page.nodes.recycleAll(bitmapsToRecycle, true);
    }
}
