package com.kickstarter.viewmodels;

import android.support.annotation.NonNull;
import android.util.Pair;

import com.kickstarter.libs.ActivityViewModel;
import com.kickstarter.libs.ApiPaginator;
import com.kickstarter.libs.Environment;
import com.kickstarter.libs.RefTag;
import com.kickstarter.libs.utils.ListUtils;
import com.kickstarter.libs.utils.StringUtils;
import com.kickstarter.models.Project;
import com.kickstarter.services.ApiClientType;
import com.kickstarter.services.DiscoveryParams;
import com.kickstarter.services.apiresponses.DiscoverEnvelope;
import com.kickstarter.ui.activities.SearchActivity;

import java.util.List;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Scheduler;
import rx.subjects.BehaviorSubject;
import rx.subjects.PublishSubject;

import static com.kickstarter.libs.rx.transformers.Transformers.takePairWhen;

public interface SearchViewModel {

  interface Inputs {
    /** Call when the next page has been invoked. */
    void nextPage();

    /** Call when a project is tapped in search results. */
    void projectClicked(final @NonNull Project project);

    /** Call when text changes in search box. */
    void search(final @NonNull String s);
  }

  interface Outputs {
    /** Emits info clicked project / reference tag pair. */
    Observable<Pair<Project, RefTag>> goToProject();

    /** Emits list of popular projects. */
    Observable<List<Project>> popularProjects();

    /** Emits list of projects matching criteria. */
    Observable<List<Project>> searchProjects();
  }

  final class ViewModel extends ActivityViewModel<SearchActivity> implements Inputs, Outputs {

    public ViewModel(final @NonNull Environment environment) {
      super(environment);

      final ApiClientType apiClient = environment.apiClient();
      final Scheduler scheduler = environment.scheduler();

      final Observable<DiscoveryParams> searchParams = this.search
        .filter(StringUtils::isPresent)
        .debounce(300, TimeUnit.MILLISECONDS, scheduler)
        .map(s -> DiscoveryParams.builder().term(s).build());

      final Observable<DiscoveryParams> popularParams = this.search
        .filter(StringUtils::isEmpty)
        .map(__ -> defaultParams)
        .startWith(defaultParams);

      final Observable<DiscoveryParams> params = Observable.merge(searchParams, popularParams);

      final ApiPaginator<Project, DiscoverEnvelope, DiscoveryParams> paginator =
        ApiPaginator.<Project, DiscoverEnvelope, DiscoveryParams>builder()
          .nextPage(this.nextPage)
          .startOverWith(params)
          .envelopeToListOfData(DiscoverEnvelope::projects)
          .envelopeToMoreUrl(env -> env.urls().api().moreProjects())
          .clearWhenStartingOver(true)
          .concater(ListUtils::concatDistinct)
          .loadWithParams(apiClient::fetchProjects)
          .loadWithPaginationPath(apiClient::fetchProjects)
          .build();

      this.search
        .filter(StringUtils::isEmpty)
        .compose(bindToLifecycle())
        .subscribe(__ -> {
          this.searchProjects.onNext(ListUtils.empty());
          this.koala.trackClearedSearchTerm();
        });

      params
        .compose(takePairWhen(paginator.paginatedData()))
        .compose(bindToLifecycle())
        .subscribe(paramsAndProjects -> {
          if (paramsAndProjects.first.sort() == defaultSort) {
            this.popularProjects.onNext(paramsAndProjects.second);
          } else {
            this.searchProjects.onNext(paramsAndProjects.second);
          }
        });

      // Track us viewing this page
      this.koala.trackSearchView();

      // Track search results and pagination
      final Observable<Integer> pageCount = paginator.loadingPage();
      final Observable<String> query = params
        .map(DiscoveryParams::term);

      final Observable<List<Project>> projects = Observable.merge(
        this.popularProjects.asObservable(),
        this.searchProjects.asObservable()
      );

      Observable.combineLatest(search, projects, Pair::create)
        .compose(bindToLifecycle())
        .compose(takePairWhen(this.tappedProject))
        .map(searchTermAndProjectsAndTappedProject -> {
          final String searchTerm = searchTermAndProjectsAndTappedProject.first.first;
          final List<Project> currentProjects = searchTermAndProjectsAndTappedProject.first.second;
          final Project tappedProject = searchTermAndProjectsAndTappedProject.second;

          if (searchTerm.length() == 0) {
            return tappedProject == currentProjects.get(0)
              ? Pair.create(tappedProject, RefTag.searchPopularFeatured())
              : Pair.create(tappedProject, RefTag.searchPopular());
          } else {
            return tappedProject == currentProjects.get(0)
              ? Pair.create(tappedProject, RefTag.searchFeatured())
              : Pair.create(tappedProject, RefTag.search());
          }
        })
        .subscribe(this.goToProject);

      query
        .compose(takePairWhen(pageCount))
        .filter(qp -> StringUtils.isPresent(qp.first))
        .compose(bindToLifecycle())
        .subscribe(qp -> this.koala.trackSearchResults(qp.first, qp.second));
    }

    private static final DiscoveryParams.Sort defaultSort = DiscoveryParams.Sort.POPULAR;
    private static final DiscoveryParams defaultParams = DiscoveryParams.builder().sort(defaultSort).build();

    private final PublishSubject<Void> nextPage = PublishSubject.create();
    private final PublishSubject<String> search = PublishSubject.create();
    private final PublishSubject<Project> tappedProject = PublishSubject.create();

    private final BehaviorSubject<List<Project>> popularProjects = BehaviorSubject.create();
    private final BehaviorSubject<List<Project>> searchProjects = BehaviorSubject.create();
    private final BehaviorSubject<Pair<Project, RefTag>> goToProject = BehaviorSubject.create();

    public final SearchViewModel.Inputs inputs = this;
    public final SearchViewModel.Outputs outputs = this;

    @Override public void nextPage() {
      this.nextPage.onNext(null);
    }
    @Override public void search(final @NonNull String s) {
      this.search.onNext(s);
    }
    @Override public void projectClicked(final @NonNull Project project) {
      this.tappedProject.onNext(project);
    }

    @Override public Observable<Pair<Project, RefTag>> goToProject() {
      return this.goToProject;
    }
    @Override public Observable<List<Project>> popularProjects() {
      return this.popularProjects;
    }
    @Override public Observable<List<Project>> searchProjects() {
      return this.searchProjects;
    }
  }
}
