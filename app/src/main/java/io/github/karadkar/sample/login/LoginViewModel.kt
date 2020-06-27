package io.github.karadkar.sample.login

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.github.karadkar.sample.R
import io.github.karadkar.sample.login.models.LoginEvent
import io.github.karadkar.sample.login.models.LoginEventResult
import io.github.karadkar.sample.login.models.LoginUiEffects
import io.github.karadkar.sample.login.models.LoginUiState
import io.github.karadkar.sample.login.repository.LoginRepository
import io.github.karadkar.sample.utils.*
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class LoginViewModel(private val repo: LoginRepository) : ViewModel() {
    private val disposable = CompositeDisposable()

    private val viewEventEmitter = PublishSubject.create<LoginEvent>()

    // persistable ui state
    val viewState = MutableLiveData<LoginUiState>()

    // on-shot ui state
    val viewEffect = SingleLiveEvent<LoginUiEffects>()

    init {
        viewEventEmitter
            .doOnNext { logInfo("event $it") }
            .share().also { event ->
                event.eventToResult()
                    .doOnNext { logInfo("result $it") }
                    .resultToViewState()
                    .doOnNext { logInfo("state $it") }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        viewState.value = it
                    }, {
                        logError("error processing event $event", t = it)
                    }).addTo(disposable)
            }
    }

    private fun Observable<LoginEvent>.eventToResult(): Observable<LoginEventResult> {
        return this.publish { obj ->
            val sources = listOf(
                obj.ofType(LoginEvent.ScreenLoadEvent::class.java).map {
                    return@map LoginEventResult.ScreenLoadResult
                },
                obj.ofType(LoginEvent.EnableDarkThemeEvent::class.java).map {
                    return@map LoginEventResult.EnableDarkThemeResult(enable = it.enable)
                },
                obj.ofType(LoginEvent.UserNameValidationCheckEvent::class.java).userNameValidationEventToResult(),
                obj.ofType(LoginEvent.PasswordValidationCheckEvent::class.java).passwordValidationEventToResult(),
                obj.ofType(LoginEvent.OnClickLoginEvent::class.java).loginEventToResult()
            )
            return@publish Observable.merge(sources)
        }
    }

    private fun Observable<LoginEventResult>.resultToViewState(): Observable<LoginUiState> {
        // gives previousUiState and new Result
        return this.scan(LoginUiState()) { state, result ->
            return@scan when (result) {
                is LoginEventResult.ScreenLoadResult -> state.copy()
                is LoginEventResult.EnableDarkThemeResult -> {
                    state.copy(enableDarkTheme = result.enable)
                }
                is LoginEventResult.UserNameValidationSuccess -> {
                    state.copy(
                        userNameError = null,
                        isUserNameValid = true
                    )
                }
                is LoginEventResult.PasswordValidationSuccess -> {
                    state.copy(
                        passwordError = null,
                        isPasswordValid = true
                    )
                }
                is LoginEventResult.ApiSuccess -> {
                    state.copy(
                        loginApiError = null,
                        loading = false
                    )
                }
                is LoginEventResult.PasswordValidationError -> {
                    state.copy(
                        passwordError = result.passwordError,
                        isPasswordValid = false
                    )
                }
                is LoginEventResult.EmailValidationError -> {
                    state.copy(
                        userNameError = result.userNameError,
                        isUserNameValid = false
                    )
                }
                is LoginEventResult.ApiLoading -> state.copy(loading = true)
                is LoginEventResult.ApiError -> {
                    state.copy(
                        loginApiError = result.message,
                        loading = false
                    )
                }
                else -> error("Event Result $result not handled")
            }
        }.distinctUntilChanged()
    }

    private fun Observable<LoginEvent.UserNameValidationCheckEvent>.userNameValidationEventToResult(): Observable<LoginEventResult> {
        return this.debounce(500L, TimeUnit.MILLISECONDS).map { event ->
            return@map if (!repo.isValidEmailId(event.username)) {
                LoginEventResult.EmailValidationError(R.string.error_invalid_email)
            } else {
                LoginEventResult.UserNameValidationSuccess
            }
        }
    }

    private fun Observable<LoginEvent.PasswordValidationCheckEvent>.passwordValidationEventToResult(): Observable<LoginEventResult> {
        return this.debounce(500L, TimeUnit.MILLISECONDS).map { event ->
            val error = checkForPasswordValidation(event)
            if (error != null) {
                return@map error
            }
            return@map LoginEventResult.PasswordValidationSuccess
        }
    }

    private fun Observable<LoginEvent.OnClickLoginEvent>.loginEventToResult(): Observable<LoginEventResult> {
        return this.switchMap { event ->
            return@switchMap repo.login(event.username, event.password)
                .map {
                    LoginEventResult.ApiSuccess(it.token) as LoginEventResult
                }
                .onErrorReturn { t ->
                    logError("error login $event", t = t)
                    LoginEventResult.ApiError(t.message ?: "api error")
                }.toObservable()
                .startWith(LoginEventResult.ApiLoading)
        }
    }

    // todo: improve readability
    private fun checkForPasswordValidation(event: LoginEvent.PasswordValidationCheckEvent): LoginEventResult? {
        return if (event.password.length < 8 || event.password.length > 16) {
            LoginEventResult.PasswordValidationError(R.string.error_password_length)
        } else if (!event.password.containsAtleastOne { Character.isUpperCase(it) }) {
            LoginEventResult.PasswordValidationError(R.string.error_password_need_uppercase)
        } else if (!event.password.containsAtleastOne { Character.isLowerCase(it) }) {
            LoginEventResult.PasswordValidationError(R.string.error_password_need_lowercase)
        } else if (!event.password.containsAtleastOne { Character.isDigit(it) }) {
            LoginEventResult.PasswordValidationError(R.string.error_password_need_digit)
        } else if (!event.password.containsAtleastOne {
                !Character.isSpaceChar(it) && !Character.isDigit(it) && !Character.isLetter(it)
            }) {
            LoginEventResult.PasswordValidationError(R.string.error_password_need_special_char)
        } else {
            null
        }
    }

    fun submitEvent(event: LoginEvent) {
        viewEventEmitter.onNext(event)
    }

    fun setNightMode(yes: Boolean) = repo.setNightMode(yes)

    fun isNightMode(): LiveData<Boolean> = repo.isNightMode()

    fun login() {
        repo.login(userName = "test@worldofplay.in", password = "Worldofplay@2020")
            .subscribe({
                logError("api call success")
            }, {
                logError("login error", it)
            }).addTo(disposable)
    }
}