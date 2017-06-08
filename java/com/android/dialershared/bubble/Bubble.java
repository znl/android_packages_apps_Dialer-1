/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.dialershared.bubble;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.SuppressLint;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.ColorInt;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.graphics.ColorUtils;
import android.support.v4.os.BuildCompat;
import android.support.v4.view.animation.FastOutLinearInInterpolator;
import android.support.v4.view.animation.LinearOutSlowInInterpolator;
import android.transition.TransitionManager;
import android.transition.TransitionValues;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewAnimator;
import com.android.dialershared.bubble.BubbleInfo.Action;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Creates and manages a bubble window from information in a {@link BubbleInfo}. Before creating, be
 * sure to check whether bubbles may be shown using {@link #canShowBubbles(Context)} and request
 * permission if necessary ({@link #getRequestPermissionIntent(Context)} is provided for
 * convenience)
 */
public class Bubble {

  // How long text should show after showText(CharSequence) is called
  private static final int SHOW_TEXT_DURATION_MILLIS = 3000;
  // How long the new window should show before destroying the old one during resize operations.
  // This ensures the new window has had time to draw first.
  private static final int WINDOW_REDRAW_DELAY_MILLIS = 50;

  private static Boolean canShowBubblesForTesting = null;

  private final Context context;
  private final WindowManager windowManager;

  private LayoutParams windowParams;

  // Initialized in factory method
  @SuppressWarnings("NullableProblems")
  @NonNull
  private BubbleInfo currentInfo;

  private boolean isShowing;
  private boolean expanded;
  private boolean textShowing;
  private boolean hideAfterText;

  private final Handler handler = new Handler();

  private ViewHolder viewHolder;

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({CollapseEnd.NOTHING, CollapseEnd.HIDE})
  private @interface CollapseEnd {
    int NOTHING = 0;
    int HIDE = 1;
  }

  /**
   * Determines whether bubbles can be shown based on permissions obtained. This should be checked
   * before attempting to create a Bubble.
   *
   * @return true iff bubbles are able to be shown.
   * @see Settings#canDrawOverlays(Context)
   */
  public static boolean canShowBubbles(@NonNull Context context) {
    return canShowBubblesForTesting != null
        ? canShowBubblesForTesting
        : Settings.canDrawOverlays(context);
  }

  @VisibleForTesting(otherwise = VisibleForTesting.NONE)
  public static void setCanShowBubblesForTesting(boolean canShowBubbles) {
    canShowBubblesForTesting = canShowBubbles;
  }

  /** Returns an Intent to request permission to show overlays */
  @NonNull
  public static Intent getRequestPermissionIntent(@NonNull Context context) {
    return new Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        new Uri.Builder().scheme("package").fragment(context.getPackageName()).build());
  }

  /** Creates instances of Bubble. The default implementation just calls the constructor. */
  @VisibleForTesting
  public interface BubbleFactory {
    Bubble createBubble(@NonNull Context context);
  }

  private static BubbleFactory bubbleFactory = Bubble::new;

  public static Bubble createBubble(@NonNull Context context, @NonNull BubbleInfo info) {
    Bubble bubble = bubbleFactory.createBubble(context);
    bubble.setBubbleInfo(info);
    return bubble;
  }

  @VisibleForTesting
  public static void setBubbleFactory(@NonNull BubbleFactory bubbleFactory) {
    Bubble.bubbleFactory = bubbleFactory;
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  Bubble(@NonNull Context context) {
    context = new ContextThemeWrapper(context, R.style.Theme_AppCompat);
    this.context = context;
    windowManager = context.getSystemService(WindowManager.class);

    viewHolder = new ViewHolder(context);
  }

  /**
   * Make the bubble visible. Will show a short entrance animation as it enters. If the bubble is
   * already showing this method does nothing.
   */
  public void show() {
    if (isShowing) {
      return;
    }

    hideAfterText = false;

    if (windowParams == null) {
      // Apps targeting O+ must use TYPE_APPLICATION_OVERLAY, which is not available prior to O.
      @SuppressWarnings("deprecation")
      @SuppressLint("InlinedApi")
      int type =
          BuildCompat.isAtLeastO()
              ? LayoutParams.TYPE_APPLICATION_OVERLAY
              : LayoutParams.TYPE_PHONE;

      windowParams =
          new LayoutParams(
              type,
              LayoutParams.FLAG_NOT_TOUCH_MODAL
                  | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                  | LayoutParams.FLAG_NOT_FOCUSABLE,
              PixelFormat.TRANSLUCENT);
      windowParams.gravity = Gravity.TOP | Gravity.LEFT;
      windowParams.x =
          context.getResources().getDimensionPixelOffset(R.dimen.bubble_initial_offset_x);
      windowParams.y =
          context.getResources().getDimensionPixelOffset(R.dimen.bubble_initial_offset_y);
      windowParams.height = LayoutParams.WRAP_CONTENT;
      windowParams.width = LayoutParams.WRAP_CONTENT;
    }

    windowManager.addView(viewHolder.getRoot(), windowParams);
    ObjectAnimator showAnimator =
        ObjectAnimator.ofPropertyValuesHolder(
            viewHolder.getPrimaryButton(),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0, 1),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0, 1));
    showAnimator.setInterpolator(new OvershootInterpolator());
    showAnimator.start();
    isShowing = true;
  }

  /**
   * Hide the button if visible. Will run a short exit animation before hiding. If the bubble is
   * currently showing text, will hide after the text is done displaying. If the bubble is not
   * visible this method does nothing.
   */
  public void hide() {
    if (!isShowing) {
      return;
    }

    if (textShowing) {
      hideAfterText = true;
      return;
    }

    if (expanded) {
      startCollapse(CollapseEnd.HIDE);
      return;
    }

    viewHolder
        .getPrimaryButton()
        .animate()
        .setInterpolator(new AnticipateInterpolator())
        .scaleX(0)
        .scaleY(0)
        .withEndAction(
            () -> {
              windowManager.removeView(viewHolder.getRoot());
              isShowing = false;
            })
        .start();
  }

  /** Returns whether the bubble is currently visible */
  public boolean isShowing() {
    return isShowing;
  }

  /**
   * Set the info for this Bubble to display
   *
   * @param bubbleInfo the BubbleInfo to display in this Bubble.
   */
  public void setBubbleInfo(@NonNull BubbleInfo bubbleInfo) {
    currentInfo = bubbleInfo;
    update();
  }

  /**
   * Update the state and behavior of actions.
   *
   * @param actions the new state of the bubble's actions
   */
  public void updateActions(@NonNull List<Action> actions) {
    currentInfo = BubbleInfo.from(currentInfo).setActions(actions).build();
    updateButtonStates();
  }

  /** Returns the currently displayed BubbleInfo */
  public BubbleInfo getBubbleInfo() {
    return currentInfo;
  }

  /**
   * Display text in the main bubble. The bubble's drawer is not expandable while text is showing,
   * and the drawer will be closed if already open.
   *
   * @param text the text to display to the user
   */
  public void showText(@NonNull CharSequence text) {
    textShowing = true;
    if (expanded) {
      startCollapse(CollapseEnd.NOTHING);
      doShowText(text);
    } else {
      // Need to transition from old bounds to new bounds manually
      ChangeOnScreenBounds transition = new ChangeOnScreenBounds();
      // Prepare and capture start values
      TransitionValues startValues = new TransitionValues();
      startValues.view = viewHolder.getPrimaryButton();
      transition.addTarget(startValues.view);
      transition.captureStartValues(startValues);

      doResize(
          () -> {
            doShowText(text);
            // Hide the text so we can animate it in
            viewHolder.getPrimaryText().setAlpha(0);

            ViewAnimator primaryButton = viewHolder.getPrimaryButton();
            // Cancel the automatic transition scheduled in doShowText
            TransitionManager.endTransitions((ViewGroup) primaryButton.getParent());
            primaryButton
                .getViewTreeObserver()
                .addOnPreDrawListener(
                    new OnPreDrawListener() {
                      @Override
                      public boolean onPreDraw() {
                        primaryButton.getViewTreeObserver().removeOnPreDrawListener(this);

                        // Prepare and capture end values
                        TransitionValues endValues = new TransitionValues();
                        endValues.view = primaryButton;
                        transition.addTarget(endValues.view);
                        transition.captureEndValues(endValues);

                        // animate the primary button bounds change
                        Animator bounds =
                            transition.createAnimator(primaryButton, startValues, endValues);

                        // Animate the text in
                        Animator alpha =
                            ObjectAnimator.ofFloat(viewHolder.getPrimaryText(), View.ALPHA, 1f);

                        AnimatorSet set = new AnimatorSet();
                        set.play(bounds).before(alpha);
                        set.start();
                        return false;
                      }
                    });
          });
    }
    handler.removeCallbacks(null);
    handler.postDelayed(
        () -> {
          textShowing = false;
          if (hideAfterText) {
            hide();
          } else {
            doResize(
                () -> viewHolder.getPrimaryButton().setDisplayedChild(ViewHolder.CHILD_INDEX_ICON));
          }
        },
        SHOW_TEXT_DURATION_MILLIS);
  }

  void onMoveStart() {
    startCollapse(CollapseEnd.NOTHING);
    viewHolder
        .getPrimaryButton()
        .animate()
        .translationZ(
            context.getResources().getDimensionPixelOffset(R.dimen.bubble_move_elevation_change));
  }

  void onMoveFinish() {
    viewHolder.getPrimaryButton().animate().translationZ(0);
  }

  void primaryButtonClick() {
    if (expanded || textShowing || currentInfo.getActions().isEmpty()) {
      try {
        currentInfo.getPrimaryAction().send();
      } catch (CanceledException e) {
        throw new RuntimeException(e);
      }
      return;
    }

    boolean onRight = (windowParams.gravity & Gravity.RIGHT) == Gravity.RIGHT;
    doResize(
        () -> {
          onLeftRightSwitch(onRight);
          viewHolder.getExpandedView().setVisibility(View.VISIBLE);
        });
    View expandedView = viewHolder.getExpandedView();
    expandedView
        .getViewTreeObserver()
        .addOnPreDrawListener(
            new OnPreDrawListener() {
              @Override
              public boolean onPreDraw() {
                expandedView.getViewTreeObserver().removeOnPreDrawListener(this);
                expandedView.setTranslationX(
                    onRight ? expandedView.getWidth() : -expandedView.getWidth());
                expandedView
                    .animate()
                    .setInterpolator(new LinearOutSlowInInterpolator())
                    .translationX(0);
                return false;
              }
            });
    setFocused(true);
    expanded = true;
  }

  void onLeftRightSwitch(boolean onRight) {
    viewHolder
        .getRoot()
        .setLayoutDirection(onRight ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    View primaryContainer = viewHolder.getRoot().findViewById(R.id.bubble_primary_container);
    ViewGroup.LayoutParams layoutParams = primaryContainer.getLayoutParams();
    ((FrameLayout.LayoutParams) layoutParams).gravity = onRight ? Gravity.RIGHT : Gravity.LEFT;
    primaryContainer.setLayoutParams(layoutParams);

    viewHolder
        .getExpandedView()
        .setBackgroundResource(
            onRight
                ? R.drawable.bubble_background_pill_rtl
                : R.drawable.bubble_background_pill_ltr);
  }

  LayoutParams getWindowParams() {
    return windowParams;
  }

  View getRootView() {
    return viewHolder.getRoot();
  }

  private void update() {
    RippleDrawable backgroundRipple =
        (RippleDrawable)
            context.getResources().getDrawable(R.drawable.bubble_ripple_circle, context.getTheme());
    int primaryTint =
        ColorUtils.compositeColors(
            context.getColor(R.color.bubble_primary_background_darken),
            currentInfo.getPrimaryColor());
    backgroundRipple.getDrawable(0).setTint(primaryTint);
    viewHolder.getPrimaryButton().setBackground(backgroundRipple);

    setBackgroundDrawable(viewHolder.getFirstButton(), primaryTint);
    setBackgroundDrawable(viewHolder.getSecondButton(), primaryTint);
    setBackgroundDrawable(viewHolder.getThirdButton(), primaryTint);

    int numButtons = currentInfo.getActions().size();
    viewHolder.getThirdButton().setVisibility(numButtons < 3 ? View.GONE : View.VISIBLE);
    viewHolder.getSecondButton().setVisibility(numButtons < 2 ? View.GONE : View.VISIBLE);

    viewHolder.getPrimaryIcon().setImageIcon(currentInfo.getPrimaryIcon());

    viewHolder
        .getExpandedView()
        .setBackgroundTintList(ColorStateList.valueOf(currentInfo.getPrimaryColor()));

    updateButtonStates();
  }

  private void setBackgroundDrawable(CheckableImageButton view, @ColorInt int color) {
    RippleDrawable itemRipple =
        (RippleDrawable)
            context
                .getResources()
                .getDrawable(R.drawable.bubble_ripple_checkable_circle, context.getTheme());
    itemRipple.getDrawable(0).setTint(color);
    view.setBackground(itemRipple);
  }

  private void updateButtonStates() {
    int numButtons = currentInfo.getActions().size();

    if (numButtons >= 1) {
      configureButton(currentInfo.getActions().get(0), viewHolder.getFirstButton());
      if (numButtons >= 2) {
        configureButton(currentInfo.getActions().get(1), viewHolder.getSecondButton());
        if (numButtons >= 3) {
          configureButton(currentInfo.getActions().get(2), viewHolder.getThirdButton());
        }
      }
    }
  }

  private void doShowText(@NonNull CharSequence text) {
    TransitionManager.beginDelayedTransition((ViewGroup) viewHolder.getPrimaryButton().getParent());
    viewHolder.getPrimaryText().setText(text);
    viewHolder.getPrimaryButton().setDisplayedChild(ViewHolder.CHILD_INDEX_TEXT);
  }

  private void configureButton(Action action, CheckableImageButton button) {
    action
        .getIcon()
        .loadDrawableAsync(
            context,
            d -> {
              button.setImageIcon(action.getIcon());
              button.setContentDescription(action.getName());
              button.setChecked(action.isChecked());
              button.setEnabled(action.isEnabled());
            },
            handler);
    button.setOnClickListener(v -> doAction(action));
  }

  private void doAction(Action action) {
    try {
      action.getAction().send();
    } catch (CanceledException e) {
      throw new RuntimeException(e);
    }
  }

  private void doResize(@Nullable Runnable operation) {
    // If we're resizing on the right side of the screen, there is an implicit move operation
    // necessary. The WindowManager does not sync the move and resize operations, so serious jank
    // would occur. To fix this, instead of resizing the window, we create a new one and destroy
    // the old one. There is a short delay before destroying the old view to ensure the new one has
    // had time to draw.
    boolean onRight = (windowParams.gravity & Gravity.RIGHT) == Gravity.RIGHT;
    ViewHolder oldViewHolder = viewHolder;
    if (onRight) {
      viewHolder = new ViewHolder(oldViewHolder.getRoot().getContext());
      update();
      viewHolder
          .getPrimaryButton()
          .setDisplayedChild(oldViewHolder.getPrimaryButton().getDisplayedChild());
      viewHolder.getPrimaryText().setText(oldViewHolder.getPrimaryText().getText());
    }

    if (operation != null) {
      operation.run();
    }

    if (onRight) {
      swapViewHolders(oldViewHolder);
    }
  }

  private void swapViewHolders(ViewHolder oldViewHolder) {
    ViewGroup root = viewHolder.getRoot();
    windowManager.addView(root, windowParams);
    root.getViewTreeObserver()
        .addOnPreDrawListener(
            new OnPreDrawListener() {
              @Override
              public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Wait a bit before removing the old view; make sure the new one has drawn over it.
                handler.postDelayed(
                    () -> windowManager.removeView(oldViewHolder.getRoot()),
                    WINDOW_REDRAW_DELAY_MILLIS);
                return true;
              }
            });
  }

  private ViewPropertyAnimator startCollapse(@CollapseEnd int collapseEndAction) {
    setFocused(false);
    boolean onRight = (windowParams.gravity & Gravity.RIGHT) == Gravity.RIGHT;
    View expandedView = viewHolder.getExpandedView();
    return expandedView
        .animate()
        .translationX(onRight ? expandedView.getWidth() : -expandedView.getWidth())
        .setInterpolator(new FastOutLinearInInterpolator())
        .withEndAction(
            () -> {
              expanded = false;
              if (collapseEndAction == CollapseEnd.HIDE) {
                hide();
              } else if (!textShowing) {
                // Don't swap the window while the user is moving it, even if we're on the right.
                // The movement will help hide the jank of the resize.
                boolean swapWindow = onRight && !viewHolder.isMoving();
                if (swapWindow) {
                  // We don't actually need to set the drawer to GONE since in the new window it
                  // will already be GONE. Just do the resize operation.
                  doResize(null);
                } else {
                  expandedView.setVisibility(View.GONE);
                }
              }
            });
  }

  private void setFocused(boolean focused) {
    if (focused) {
      windowParams.flags &= ~LayoutParams.FLAG_NOT_FOCUSABLE;
    } else {
      windowParams.flags |= LayoutParams.FLAG_NOT_FOCUSABLE;
    }
    windowManager.updateViewLayout(getRootView(), windowParams);
  }

  private class ViewHolder {

    public static final int CHILD_INDEX_ICON = 0;
    public static final int CHILD_INDEX_TEXT = 1;

    private final MoveHandler moveHandler;
    private final WindowRoot root;
    private final ViewAnimator primaryButton;
    private final ImageView primaryIcon;
    private final TextView primaryText;

    private final CheckableImageButton firstButton;
    private final CheckableImageButton secondButton;
    private final CheckableImageButton thirdButton;
    private final View expandedView;

    public ViewHolder(Context context) {
      // Window root is not in the layout file so that the inflater has a view to inflate into
      this.root = new WindowRoot(context);
      LayoutInflater inflater = LayoutInflater.from(root.getContext());
      View contentView = inflater.inflate(R.layout.bubble_base, root, true);
      expandedView = contentView.findViewById(R.id.bubble_expanded_layout);
      primaryButton = contentView.findViewById(R.id.bubble_button_primary);
      primaryIcon = contentView.findViewById(R.id.bubble_icon_primary);
      primaryText = contentView.findViewById(R.id.bubble_text);

      firstButton = contentView.findViewById(R.id.bubble_icon_first);
      secondButton = contentView.findViewById(R.id.bubble_icon_second);
      thirdButton = contentView.findViewById(R.id.bubble_icon_third);

      root.setOnBackPressedListener(
          () -> {
            if (isShowing && expanded) {
              startCollapse(CollapseEnd.NOTHING);
              return true;
            }
            return false;
          });
      root.setOnTouchListener(
          (v, event) -> {
            if (expanded && event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
              startCollapse(CollapseEnd.NOTHING);
              return true;
            }
            return false;
          });
      moveHandler = new MoveHandler(primaryButton, Bubble.this);
    }

    public ViewGroup getRoot() {
      return root;
    }

    public ViewAnimator getPrimaryButton() {
      return primaryButton;
    }

    public ImageView getPrimaryIcon() {
      return primaryIcon;
    }

    public TextView getPrimaryText() {
      return primaryText;
    }

    public CheckableImageButton getFirstButton() {
      return firstButton;
    }

    public CheckableImageButton getSecondButton() {
      return secondButton;
    }

    public CheckableImageButton getThirdButton() {
      return thirdButton;
    }

    public View getExpandedView() {
      return expandedView;
    }

    public boolean isMoving() {
      return moveHandler.isMoving();
    }
  }
}
