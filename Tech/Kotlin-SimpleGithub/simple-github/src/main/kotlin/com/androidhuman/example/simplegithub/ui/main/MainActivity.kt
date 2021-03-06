package com.androidhuman.example.simplegithub.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidhuman.example.simplegithub.R
import com.androidhuman.example.simplegithub.api.model.GithubRepo
import com.androidhuman.example.simplegithub.data.provideSearchHistoryDao
import com.androidhuman.example.simplegithub.extensions.plusAssign
import com.androidhuman.example.simplegithub.extensions.runOnIoScheduler
import com.androidhuman.example.simplegithub.rx.AutoActivatedDisposable
import com.androidhuman.example.simplegithub.rx.AutoClearedDisposable
import com.androidhuman.example.simplegithub.ui.repo.RepositoryActivity
import com.androidhuman.example.simplegithub.ui.search.SearchActivity
import com.androidhuman.example.simplegithub.ui.search.SearchAdapter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.startActivity

class MainActivity : AppCompatActivity(), SearchAdapter.ItemClickListener {

    // 어댑터 프로퍼티를 추가합니다.
    internal val adapter by lazy { SearchAdapter().apply { setItemClickListener(this@MainActivity) } }

    // 최근 조회한 저장소를 담당하는 데이터 접근 객체 프로퍼티를 추가합니다.
    internal val searchHistoryDao by lazy { provideSearchHistoryDao(this) }

    // 디스포저블을 관리하는 프로퍼티를 추가합니다.
    internal val disposables = AutoClearedDisposable(this)

    // 액티비티가 완전히 종료되기 전까지 이벤트를 계속 받기 위해 추가합니다.
    internal val viewDisposables = AutoClearedDisposable(lifecycleOwner = this, alwaysClearOnStop = false)

    // MainViewModel 을 생성하기 위해 필요한 뷰모델 팩토리 클래스의 인스턴스를 생성합니다.
    internal val viewModelFactory by lazy { MainViewModelFactory(provideSearchHistoryDao(this)) }

    lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // MainViewModel 의 인스턴스를 받습니다.
        viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]

        // 생명주기 이벤트 옵저버를 등록합니다.
        lifecycle += disposables

        // viewDisposables 에서 이 액티비티의 생명주기 이벤트를 받도록 합니다.
        lifecycle += viewDisposables

        // 액티비티가 활성 상태일 때만
        // 데이터베이스에 저장된 저장소 조회 기록을 받도록 합니다.
        lifecycle += AutoActivatedDisposable(this) {
            viewModel.searchHistory
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { items ->
                        with(adapter) {
                            if (items.isEmpty) {
                                clearItems()
                            } else {
                                setItems(items.value)
                            }
                            notifyDataSetChanged()
                        }
                    }
        }

        // ViewModel 패턴을 추가하며 주석 처리.
//        lifecycle += object : LifecycleObserver {
//
//            // onStart() 콜백 함수가 호출되면 fetchSearchHistory() 함수를 호출합니다.
//            @OnLifecycleEvent(Lifecycle.Event.ON_START)
//            fun fetch() {
//                fetchSearchHistory()
//            }
//        }

        btnActivityMainSearch.setOnClickListener { // 저장소 검색 액티비티를 호출합니다.
            // 호출할 액티비티만 명시합니다.
            startActivity<SearchActivity>()
        }

        with(rvActivityMainList) {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // 메시지 이벤트를 구독합니다.
        viewDisposables += viewModel.message
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { message ->
                    if (message.isEmpty) {
                        // 빈 메시지를 받은 경우 표시되고 있는 메시지를 화면에서 숨깁니다.
                        hideMessage()
                    } else {
                        // 유효한 메시지를 받은 경우 화면에 메시지를 표시합니다.
                        showMessage(message.value)
                    }
                }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_activity_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // 'Clear all' 메뉴를 선택하면 조회했던 저장소 기록을 모두 삭제합니다.
        if (R.id.menu_activity_main_clear_all == item.itemId) {
            // ViewModel 패턴을 추가하며 주석 처리.
            disposables += viewModel.clearSearchHistory()
//            clearAll()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onItemClick(repository: GithubRepo) {
        startActivity<RepositoryActivity>(
                RepositoryActivity.KEY_USER_LOGIN to repository.owner.login,
                RepositoryActivity.KEY_REPO_NAME to repository.name
        )
    }

    // 데이터베이스에 저장되어 있는 저장소 목록을 불러오는 작업을 반환합니다.
    // SearchHistoryDao.getHistory() 함수는 Flowable 형태로 데이터를 반환하므로,
    // 데이터베이스에 저장된 자료가 바뀌면 즉시 업데이트된 정보가 새로 전달됩니다.
    private fun fetchSearchHistory(): Disposable = searchHistoryDao.getHistory()
            // 메인 스레드에서 호출하면 Room 에서 오류를 발생시키므로 IO 스레드에서 작업을 수행합니다.
            .subscribeOn(Schedulers.io())
            // 결과를 받아 뷰에 업데이트해야 하므로 메인 스레드(UI 스레드)에서 결과를 처리합니다.
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ items ->
                // 어댑터 갱신
                with(adapter) {
                    setItems(items)
                    notifyDataSetChanged()
                }

                // 저장된 데이터의 유무에 따라 오류 메시지를 표시하거나 감춥니다.
                if (items.isEmpty()) {
                    showMessage(getString(R.string.no_recent_repositories))
                } else {
                    hideMessage()
                }
            }) {
                showMessage(it.message)
            }

    // 데이터베이스에 저장되어 있는 모든 저장소 기록을 삭제합니다.
    private fun clearAll() {
        // 메인 스레드에서 실행하면 오류가 발생하므로,
        // 앞에서 작성한 runOnIoScheduler() 함수를 사용하여 IO 스레드에서 작업을 실행합니다
        disposables += runOnIoScheduler { searchHistoryDao.clearAll() }
    }

    private fun showMessage(message: String?) {
        with(tvActivityMainMessage) {
            text = message ?: "Unexpected error."
            visibility = View.VISIBLE
        }
    }

    private fun hideMessage() {
        with(tvActivityMainMessage) {
            text = ""
            visibility = View.GONE
        }
    }
}