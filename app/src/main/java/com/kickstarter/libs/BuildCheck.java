package com.kickstarter.libs;

import android.support.annotation.NonNull;

import com.kickstarter.libs.rx.transformers.Transformers;
import com.kickstarter.services.WebClientType;
import com.kickstarter.viewmodels.DiscoveryViewModel;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import timber.log.Timber;

public interface BuildCheck {
  void bind(final @NonNull DiscoveryViewModel viewModel, final @NonNull WebClientType client);

  BuildCheck DEFAULT = (viewModel, client) -> {
    final Subscription subscription = client.pingBeta()
      .compose(Transformers.combineLatestPair(viewModel.view()))
      .filter(v -> v.first.newerBuildAvailable())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(v -> v.second.showBuildAlert(v.first), e -> Timber.e(e.toString()));

    viewModel.addSubscription(subscription);
  };
}
