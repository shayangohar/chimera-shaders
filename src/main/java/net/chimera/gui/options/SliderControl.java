package net.chimera.gui.options;

/**
 * The slider being dragged and the one being edited from the keyboard, so the screen can end either at the
 * right moment. A drag ends on the mouse release, which the screen sees while vanilla stops tracking held
 * buttons. A keyboard edit commits before Apply, Done or a page change reads the queue, and ends without a
 * commit before Cancel empties it.
 */
final class SliderControl {
    private SliderCell dragging;
    private SliderCell editing;

    void dragging(SliderCell cell) {
        this.dragging = cell;
    }

    void editing(SliderCell cell) {
        this.editing = cell;
    }

    /** The mouse button came up at {@code mouseX}: the dragged slider takes that bucket. */
    void release(int mouseX) {
        SliderCell cell = this.dragging;
        this.dragging = null;
        if (cell != null) cell.release(mouseX);
    }

    /** Ends a keyboard edit, queueing its value or dropping it. */
    void finishEdits(boolean commit) {
        SliderCell cell = this.editing;
        this.editing = null;
        if (cell != null) cell.endEdit(commit);
    }

    /** Ends a drag whose release this view will never see, queueing the value the slider shows. */
    void endDrag() {
        SliderCell cell = this.dragging;
        this.dragging = null;
        if (cell != null) cell.endDrag();
    }
}
