package io.github.sds100.keymapper

import android.view.KeyEvent
import android.view.Surface
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import com.hadilq.liveevent.LiveEvent
import io.github.sds100.keymapper.data.model.*
import io.github.sds100.keymapper.data.model.Trigger.Companion.DOUBLE_PRESS
import io.github.sds100.keymapper.data.model.Trigger.Companion.LONG_PRESS
import io.github.sds100.keymapper.data.model.Trigger.Companion.SHORT_PRESS
import io.github.sds100.keymapper.data.model.Trigger.Key.Companion.FLAG_DO_NOT_CONSUME_KEY_EVENT
import io.github.sds100.keymapper.util.*
import io.github.sds100.keymapper.util.delegate.ConstraintDelegate
import io.github.sds100.keymapper.util.delegate.KeymapDetectionDelegate
import io.github.sds100.keymapper.util.delegate.KeymapDetectionPreferences
import io.github.sds100.keymapper.util.result.Success
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNull
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import junitparams.naming.TestCaseName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.withTimeout
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.greaterThan
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import splitties.bitflags.withFlag
import kotlin.random.Random

/**
 * Created by sds100 on 17/05/2020.
 */

@ExperimentalCoroutinesApi
@RunWith(JUnitParamsRunner::class)
class KeymapDetectionDelegateTest {

    companion object {
        private const val FAKE_KEYBOARD_DESCRIPTOR = "fake_keyboard"
        private const val FAKE_KEYBOARD_DEVICE_ID = 123
        private const val FAKE_HEADPHONE_DESCRIPTOR = "fake_headphone"

        private const val FAKE_PACKAGE_NAME = "test_package"

        private val FAKE_DESCRIPTORS = arrayOf(
            FAKE_KEYBOARD_DESCRIPTOR,
            FAKE_HEADPHONE_DESCRIPTOR
        )

        private const val LONG_PRESS_DELAY = 500
        private const val DOUBLE_PRESS_DELAY = 300
        private const val FORCE_VIBRATE = false
        private const val REPEAT_DELAY = 50
        private const val HOLD_DOWN_DELAY = 400
        private const val SEQUENCE_TRIGGER_TIMEOUT = 2000
        private const val VIBRATION_DURATION = 100
        private const val HOLD_DOWN_DURATION = 1000

        private val TEST_ACTION = Action(ActionType.SYSTEM_ACTION, SystemAction.TOGGLE_FLASHLIGHT)
        private val TEST_ACTION_2 = Action(ActionType.APP, Constants.PACKAGE_NAME)

        private val TEST_ACTIONS = setOf(
            TEST_ACTION,
            TEST_ACTION_2
        )
    }

    private lateinit var delegate: KeymapDetectionDelegate
    private lateinit var performActionTest: LiveDataTestWrapper<PerformAction>
    private lateinit var imitateButtonPressTest: LiveDataTestWrapper<ImitateButtonPress>
    private lateinit var eventStream: LiveDataTestWrapper<Event>
    private var currentPackage = ""

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = TestCoroutineDispatcher()
    private val coroutineScope = TestCoroutineScope(testDispatcher)

    @Before
    fun init() {
        val iClock = object : IClock {
            override val currentTime: Long
                get() = coroutineScope.currentTime
        }

        val iConstraintState = object : IConstraintState {
            override val currentPackageName: String
                get() = currentPackage

            override fun isBluetoothDeviceConnected(address: String) = true
            override val isScreenOn = true
            override val orientation = Surface.ROTATION_0
            override val highestPriorityPackagePlayingMedia: String?
                get() = null
        }

        val constraintDelegate = ConstraintDelegate(iConstraintState)

        val iActionError = object : IActionError {
            override fun canActionBePerformed(action: Action) = Success(action)
        }

        val preferences = KeymapDetectionPreferences(
            LONG_PRESS_DELAY,
            DOUBLE_PRESS_DELAY,
            HOLD_DOWN_DELAY,
            REPEAT_DELAY,
            SEQUENCE_TRIGGER_TIMEOUT,
            VIBRATION_DURATION,
            HOLD_DOWN_DURATION,
            FORCE_VIBRATE
        )

        delegate = KeymapDetectionDelegate(
            coroutineScope,
            preferences,
            iClock,
            iActionError,
            constraintDelegate
        )

        performActionTest = LiveDataTestWrapper(delegate.performAction)
        imitateButtonPressTest = LiveDataTestWrapper(delegate.imitateButtonPress)

        val eventStreamLiveData = LiveEvent<Event>().apply {
            addSource(delegate.performAction) {
                value = it
            }

            addSource(delegate.imitateButtonPress) {
                value = it
            }

            addSource(delegate.vibrate) {
                value = it
            }
        }

        eventStream = LiveDataTestWrapper(eventStreamLiveData)
    }

    @After
    fun tearDown() {
        delegate.keymapListCache = listOf()
        delegate.reset()

        performActionTest.reset()
    }

    /**
     * See #626 in issue tracker
     *
     * Key maps:
     * 1. Short press, single key, action that repeats until released
     * 2. Long press, same single key, action that repeats
     *
     * Expected:
     * On short press: trigger but don't start repeating #1 and don't trigger #2
     * On long press: don't trigger #1 and start repeating #2
     */
    @Test
    fun `don't initialise repeating if repeat when trigger is released after failed long press`() =
        coroutineScope.runBlockingTest {
            //given
            val trigger1 = parallelTrigger(Trigger.Key(keyCode = 1))
            val action1 = Action.keyEventAction(keyCode = 2).copy(
                flags = Action.ACTION_FLAG_REPEAT
            )

            val trigger2 = parallelTrigger(Trigger.Key(clickType = LONG_PRESS, keyCode = 1))
            val action2 = Action.keyEventAction(keyCode = 3).copy(
                flags = Action.ACTION_FLAG_REPEAT,
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = trigger1, actionList = listOf(action1)),
                KeyMap(1, trigger = trigger2, actionList = listOf(action2))
            )

            //when short press
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray())
            delay(2000)// let it try to repeat

            //then
            assertThat(performActionTest.history.size, `is`(1))
            assert(performActionTest.history.all { it.action == action1 })

            //when long press
            performActionTest.reset()
            mockParallelTriggerKeys(*trigger2.keys.toTypedArray(), delay = 2000)//let it repeat

            //then
            assertThat(performActionTest.history.size, greaterThan(1))
            assert(performActionTest.history.all { it.action == action2 }) {
                "not all actions are the action for the long press trigger"
            }
        }

    /**
     * See #626 in issue tracker
     *
     * Key maps:
     * 1. Short press, single key, action that repeats until released
     * 2. Double press, same single key, action that doesn't repeat
     *
     * Expected:
     * On short press: trigger but don't start repeating #1 and don't trigger #2
     * On double press: don't trigger #1 and trigger #2
     */
    @Test
    fun `don't initialise repeating if repeat when trigger is released after failed failed double press`() =
        coroutineScope.runBlockingTest {
            //given
            val trigger1 = parallelTrigger(Trigger.Key(keyCode = 1))
            val action1 = Action.keyEventAction(keyCode = 2).copy(
                flags = Action.ACTION_FLAG_REPEAT
            )

            val trigger2 = sequenceTrigger(Trigger.Key(clickType = DOUBLE_PRESS, keyCode = 1))
            val action2 = Action.keyEventAction(keyCode = 3)

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = trigger1, actionList = listOf(action1)),
                KeyMap(1, trigger = trigger2, actionList = listOf(action2))
            )

            //when short press
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray())
            delay(2000)// let it repeat

            //then
            assertThat(performActionTest.history.size, `is`(1))
            assert(performActionTest.history.all { it.action == action1 })

            //when double press
            performActionTest.reset()
            mockTriggerKeyInput(trigger2.keys[0])

            //then
            assertThat(performActionTest.history.map { it.action }, `is`(listOf(action2)))
        }

    /**
     * See #626 in issue tracker
     *
     * Key maps:
     * 1. Short press, single key, action that repeats until released
     * 2. Long press, same single key, action that repeats
     * 3. Double press, same single key, action that doesn't repeat
     *
     * Expected:
     * On short press: trigger but don't repeat #1, don't trigger #2 don't trigger #3
     * On long press: don't trigger #1, start repeating #2, don't trigger #3
     * On double press: don't trigger #1, don't trigger #2, trigger #3
     */
    @Test
    fun `don't initialise repeating if repeat when trigger is released after failed double press and failed long press`() =
        coroutineScope.runBlockingTest {
            //given
            val trigger1 = parallelTrigger(Trigger.Key(keyCode = 1))
            val action1 = Action.keyEventAction(keyCode = 2).copy(
                flags = Action.ACTION_FLAG_REPEAT
            )

            val trigger2 = parallelTrigger(Trigger.Key(clickType = LONG_PRESS, keyCode = 1))
            val action2 = Action.keyEventAction(keyCode = 3).copy(
                flags = Action.ACTION_FLAG_REPEAT,
            )

            val trigger3 = sequenceTrigger(Trigger.Key(clickType = DOUBLE_PRESS, keyCode = 1))
            val action3 = Action.keyEventAction(keyCode = 4)

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = trigger1, actionList = listOf(action1)),
                KeyMap(1, trigger = trigger2, actionList = listOf(action2)),
                KeyMap(2, trigger = trigger3, actionList = listOf(action3))
            )

            //when short press
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray())
            advanceUntilIdle()

            //then
            assertThat(performActionTest.history.size, `is`(1))
            assert(performActionTest.history.all { it.action == action1 })

            //when long press
            performActionTest.reset()
            mockParallelTriggerKeys(*trigger2.keys.toTypedArray(), delay = 2000)//let it repeat

            //then
            assertThat(performActionTest.history.size, greaterThan(1))
            assert(performActionTest.history.all { it.action == action2 }) {
                "not all actions are the action for the long press trigger"
            }

            //when double press
            performActionTest.reset()
            mockTriggerKeyInput(trigger3.keys[0])

            //then
            assertThat(performActionTest.history.map { it.action }, `is`(listOf(action3)))
        }

    /**
     * See #626 in issue tracker
     *
     * Key maps:
     * 1. Short press, single key, action that repeats until pressed again
     * 2. Long press, same single key, action that repeats
     *
     * Expected:
     * On short press: start repeating #1 and don't trigger #2
     * On long press: don't trigger #1 and start repeating #2
     */
    @Test
    fun `initialise repeating if repeat until pressed again on failed long press`() =
        coroutineScope.runBlockingTest {
            //given
            val trigger1 = parallelTrigger(Trigger.Key(keyCode = 1))
            val action1 = Action.keyEventAction(keyCode = 2).copy(
                flags = Action.ACTION_FLAG_REPEAT,
                extras = listOf(
                    Extra(
                        Action.EXTRA_CUSTOM_STOP_REPEAT_BEHAVIOUR,
                        Action.STOP_REPEAT_BEHAVIOUR_TRIGGER_PRESSED_AGAIN.toString()
                    )
                )
            )

            val trigger2 = parallelTrigger(Trigger.Key(clickType = LONG_PRESS, keyCode = 1))
            val action2 = Action.keyEventAction(keyCode = 3).copy(
                flags = Action.ACTION_FLAG_REPEAT,
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = trigger1, actionList = listOf(action1)),
                KeyMap(1, trigger = trigger2, actionList = listOf(action2))
            )

            //when short press
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray())
            delay(2000)// let it repeat

            //then
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray()) //press the key again to stop it repeatin

            assertThat(performActionTest.history.size, greaterThan(2))
            assert(performActionTest.history.all { it.action == action1 })

            //when long press
            performActionTest.reset()
            mockParallelTriggerKeys(*trigger2.keys.toTypedArray(), delay = 2000)//let it repeat

            //then
            assertThat(performActionTest.history.size, greaterThan(1))
            assert(performActionTest.history.all { it.action == action2 }) {
                "not all actions are the action for the long press trigger"
            }
        }

    /**
     * See #626 in issue tracker
     *
     * Key maps:
     * 1. Short press, single key, action that repeats until pressed again
     * 2. Double press, same single key, action that doesn't repeat
     *
     * Expected:
     * On short press: start repeating #1 and don't trigger #2
     * On double press: don't trigger #1 and trigger #2
     */
    @Test
    fun `initialise repeating if repeat until pressed again on failed double press`() =
        coroutineScope.runBlockingTest {
            //given
            val trigger1 = parallelTrigger(Trigger.Key(keyCode = 1))
            val action1 = Action.keyEventAction(keyCode = 2).copy(
                flags = Action.ACTION_FLAG_REPEAT,
                extras = listOf(
                    Extra(
                        Action.EXTRA_CUSTOM_STOP_REPEAT_BEHAVIOUR,
                        Action.STOP_REPEAT_BEHAVIOUR_TRIGGER_PRESSED_AGAIN.toString()
                    )
                )
            )

            val trigger2 = sequenceTrigger(Trigger.Key(clickType = DOUBLE_PRESS, keyCode = 1))
            val action2 = Action.keyEventAction(keyCode = 3)

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = trigger1, actionList = listOf(action1)),
                KeyMap(1, trigger = trigger2, actionList = listOf(action2))
            )

            //when short press
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray())
            delay(2000)// let it repeat

            //then
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray()) //press the key again to stop it repeatin
            advanceUntilIdle()

            assertThat(performActionTest.history.size, greaterThan(2))
            assert(performActionTest.history.all { it.action == action1 })

            //when double press
            performActionTest.reset()
            mockTriggerKeyInput(trigger2.keys[0])
            advanceUntilIdle()

            //then
            assertThat(performActionTest.history.map { it.action }, `is`(listOf(action2)))
        }

    /**
     * See #626 in issue tracker
     *
     * Key maps:
     * 1. Short press, single key, action that repeats until pressed again
     * 2. Long press, same single key, action that repeats
     * 3. Double press, same single key, action that doesn't repeat
     *
     * Expected:
     * On short press: start repeating #1, don't trigger #2 don't trigger #3
     * On long press: don't trigger #1, start repeating #2, don't trigger #3
     * On double press: don't trigger #1, don't trigger #2, trigger #3
     */
    @Test
    fun `initialise repeating if repeat until pressed again on failed double press and failed long press`() =
        coroutineScope.runBlockingTest {
            //given
            val trigger1 = parallelTrigger(Trigger.Key(keyCode = 1))
            val action1 = Action.keyEventAction(keyCode = 2).copy(
                flags = Action.ACTION_FLAG_REPEAT,
                extras = listOf(
                    Extra(
                        Action.EXTRA_CUSTOM_STOP_REPEAT_BEHAVIOUR,
                        Action.STOP_REPEAT_BEHAVIOUR_TRIGGER_PRESSED_AGAIN.toString()
                    )
                )
            )

            val trigger2 = parallelTrigger(Trigger.Key(clickType = LONG_PRESS, keyCode = 1))
            val action2 = Action.keyEventAction(keyCode = 3).copy(
                flags = Action.ACTION_FLAG_REPEAT,
            )

            val trigger3 = sequenceTrigger(Trigger.Key(clickType = DOUBLE_PRESS, keyCode = 1))
            val action3 = Action.keyEventAction(keyCode = 4)

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = trigger1, actionList = listOf(action1)),
                KeyMap(1, trigger = trigger2, actionList = listOf(action2)),
                KeyMap(2, trigger = trigger3, actionList = listOf(action3))
            )

            //when short press
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray())

            delay(2000)// let it repeat

            //then
            mockParallelTriggerKeys(*trigger1.keys.toTypedArray()) //press the key again to stop it repeatin
            advanceUntilIdle()

            assertThat(performActionTest.history.size, greaterThan(2))
            assert(performActionTest.history.all { it.action == action1 })

            //when long press
            performActionTest.reset()
            mockParallelTriggerKeys(*trigger2.keys.toTypedArray(), delay = 2000) //let it repeat

            //then
            assertThat(performActionTest.history.size, greaterThan(1))
            assert(performActionTest.history.all { it.action == action2 }) {
                "not all actions are the action for the long press trigger. actual actions: ${performActionTest.history}"
            }

            delay(1000) // have a delay after a long press of the key is released so a double press isn't detected

            //when double press
            performActionTest.reset()
            mockTriggerKeyInput(trigger3.keys[0])

            //then
            assertThat(performActionTest.history.map { it.action }, `is`(listOf(action3)))
        }

    /**
     * this helped fix issue #608
     */
    @Test
    fun `short press key and double press same key sequence trigger, double press key, don't perform action`() =
        coroutineScope.runBlockingTest {
            val trigger = sequenceTrigger(
                Trigger.Key(KeyEvent.KEYCODE_A),
                Trigger.Key(KeyEvent.KEYCODE_A, clickType = DOUBLE_PRESS)
            )

            delegate.keymapListCache = listOf(
                KeyMap(
                    id = 0,
                    trigger = trigger,
                    actionList = listOf(TEST_ACTION)
                )
            )

            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_A, clickType = DOUBLE_PRESS))

            assertThat(performActionTest.history, `is`(emptyList()))

            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_A))
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_A, clickType = DOUBLE_PRESS))

            assertThat(performActionTest.history.map { it.action }, `is`(listOf(TEST_ACTION)))
        }

    /**
     * issue #563
     */
    @Test
    fun sendKeyEventActionWhenImitatingButtonPresses() = coroutineScope.runBlockingTest {
        val trigger = parallelTrigger(
            Trigger.Key(KeyEvent.KEYCODE_META_LEFT, deviceId = FAKE_KEYBOARD_DESCRIPTOR)
        )

        val action = Action.keyCodeAction(KeyEvent.KEYCODE_META_LEFT)
            .copy(flags = Action.ACTION_FLAG_HOLD_DOWN)

        delegate.keymapListCache = listOf(
            KeyMap(0, trigger, listOf(action))
        )
        val metaState = KeyEvent.META_META_ON.withFlag(KeyEvent.META_META_LEFT_ON)

        inputKeyEvent(
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.ACTION_DOWN,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState,
            FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 117
        )
        inputKeyEvent(
            KeyEvent.KEYCODE_E,
            KeyEvent.ACTION_DOWN,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState,
            FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 33
        )
        inputKeyEvent(
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.ACTION_UP,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState,
            deviceId = FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 117
        )
        inputKeyEvent(
            KeyEvent.KEYCODE_E,
            KeyEvent.ACTION_UP,
            FAKE_KEYBOARD_DESCRIPTOR,
            deviceId = FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 33
        )

        val expectedEvents = listOf(
            PerformAction(action, metaState, KeyEventAction.DOWN),
            ImitateButtonPress(
                KeyEvent.KEYCODE_E,
                metaState,
                FAKE_KEYBOARD_DEVICE_ID,
                KeyEventAction.DOWN,
                scanCode = 33
            ),
            PerformAction(action, 0, KeyEventAction.UP),
            ImitateButtonPress(
                KeyEvent.KEYCODE_E,
                metaState = 0,
                FAKE_KEYBOARD_DEVICE_ID,
                KeyEventAction.UP,
                scanCode = 33
            )
        )

        assertThat(eventStream.history, `is`(expectedEvents))
        eventStream.reset()

        inputKeyEvent(
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.ACTION_DOWN,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState,
            FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 117
        )
        inputKeyEvent(
            KeyEvent.KEYCODE_E,
            KeyEvent.ACTION_DOWN,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState,
            FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 33
        )
        inputKeyEvent(
            KeyEvent.KEYCODE_E,
            KeyEvent.ACTION_UP,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState,
            FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 33
        )
        inputKeyEvent(
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.ACTION_UP,
            FAKE_KEYBOARD_DESCRIPTOR,
            metaState = 0,
            FAKE_KEYBOARD_DEVICE_ID,
            scanCode = 117
        )

        advanceUntilIdle()

        val expectedEvents2 = listOf(
            PerformAction(action, metaState, KeyEventAction.DOWN),
            ImitateButtonPress(
                KeyEvent.KEYCODE_E,
                metaState,
                FAKE_KEYBOARD_DEVICE_ID,
                KeyEventAction.DOWN,
                scanCode = 33
            ),
            ImitateButtonPress(
                KeyEvent.KEYCODE_E,
                metaState,
                FAKE_KEYBOARD_DEVICE_ID,
                KeyEventAction.UP,
                scanCode = 33
            ),
            PerformAction(action, additionalMetaState = 0, KeyEventAction.UP),
        )

        assertThat(eventStream.history, `is`(expectedEvents2))
    }

    @Test
    fun `parallel trigger with 2 keys and the 2nd key is another trigger, press 2 key trigger, only the action for 2 key trigger should be performed `() =
        coroutineScope.runBlockingTest {
            //GIVEN
            val twoKeyTrigger = parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_SHIFT_LEFT),
                Trigger.Key(KeyEvent.KEYCODE_A)
            )

            val oneKeyTrigger = undefinedTrigger(
                Trigger.Key(KeyEvent.KEYCODE_A)
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = oneKeyTrigger, actionList = listOf(TEST_ACTION_2)),
                KeyMap(1, trigger = twoKeyTrigger, actionList = listOf(TEST_ACTION))
            )

            //test 1. test triggering 2 key trigger
            //WHEN
            inputKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN)
            inputKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN)

            inputKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP)
            inputKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
            advanceUntilIdle()

            //THEN

            assertThat(performActionTest.history.map { it.action }, `is`(listOf(TEST_ACTION)))
            performActionTest.reset()

            //test 2. test triggering 1 key trigger
            //WHEN
            inputKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN)

            inputKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
            advanceUntilIdle()

            //THEN

            assertThat(performActionTest.history.map { it.action }, `is`(listOf(TEST_ACTION_2)))
            performActionTest.reset()
        }

    @Test
    fun `trigger for a specific device and trigger for any device, input trigger from a different device, only detect trigger for any device`() =
        coroutineScope.runBlockingTest {
            //GIVEN
            val triggerHeadphone = Trigger(
                keys = listOf(
                    Trigger.Key(
                        KeyEvent.KEYCODE_A,
                        deviceId = FAKE_HEADPHONE_DESCRIPTOR
                    )
                ),
                mode = Trigger.UNDEFINED
            )

            val triggerAnyDevice = Trigger(
                keys = listOf(
                    Trigger.Key(
                        KeyEvent.KEYCODE_A,
                        deviceId = Trigger.Key.DEVICE_ID_ANY_DEVICE
                    )
                ),
                mode = Trigger.UNDEFINED
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = triggerHeadphone, actionList = listOf(TEST_ACTION)),
                KeyMap(1, trigger = triggerAnyDevice, actionList = listOf(TEST_ACTION_2))
            )

            //WHEN
            mockTriggerKeyInput(
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    deviceId = FAKE_KEYBOARD_DESCRIPTOR
                )
            )
            advanceUntilIdle()

            //THEN
            assertThat(performActionTest.history.map { it.action }, `is`(listOf(TEST_ACTION_2)))
        }

    @Test
    fun `trigger for a specific device, input trigger from a different device, dont detect trigger`() =
        coroutineScope.runBlockingTest {
            //GIVEN
            val triggerHeadphone = Trigger(
                keys = listOf(
                    Trigger.Key(
                        KeyEvent.KEYCODE_A,
                        deviceId = FAKE_HEADPHONE_DESCRIPTOR
                    )
                ),
                mode = Trigger.UNDEFINED
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = triggerHeadphone, actionList = listOf(TEST_ACTION))
            )

            //WHEN
            mockTriggerKeyInput(
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    deviceId = FAKE_KEYBOARD_DESCRIPTOR
                )
            )
            advanceUntilIdle()

            //THEN
            assertThat(performActionTest.history, `is`(emptyList()))
        }

    @Test
    fun `long press trigger and action with Hold Down until pressed again flag, input valid long press, hold down until long pressed again`() =
        coroutineScope.runBlockingTest {
            //GIVEN
            val trigger = Trigger(
                keys = listOf(Trigger.Key(KeyEvent.KEYCODE_A, clickType = LONG_PRESS)),
                mode = Trigger.UNDEFINED
            )

            val action = Action.keyCodeAction(KeyEvent.KEYCODE_B).copy(
                flags = Action.ACTION_FLAG_HOLD_DOWN,
                extras = listOf(
                    Extra(
                        Action.EXTRA_CUSTOM_HOLD_DOWN_BEHAVIOUR,
                        Action.STOP_HOLD_DOWN_BEHAVIOR_TRIGGER_PRESSED_AGAIN.toString()
                    )
                )
            )

            val keymap = KeyMap(
                0,
                trigger = trigger,
                actionList = listOf(action)
            )

            delegate.keymapListCache = listOf(keymap)

            //WHEN
            mockTriggerKeyInput(trigger.keys[0])
            advanceUntilIdle()

            //THEN
            assertThat(delegate.performAction.value?.action, `is`(action))

            assertThat(delegate.performAction.value?.keyEventAction, `is`(KeyEventAction.DOWN))

            //WHEN
            mockTriggerKeyInput(trigger.keys[0])
            advanceUntilIdle()

            assertThat(delegate.performAction.value?.action, `is`(action))

            assertThat(delegate.performAction.value?.keyEventAction, `is`(KeyEventAction.UP))
        }

    /**
     * #478
     */
    @Test
    fun `trigger with modifier key and modifier keycode action, don't include metastate from the trigger modifier key when an unmapped modifier key is pressed`() =
        coroutineScope.runBlockingTest {
            val trigger = undefinedTrigger(Trigger.Key(KeyEvent.KEYCODE_CTRL_LEFT))

            delegate.keymapListCache = listOf(
                KeyMap(
                    0,
                    trigger,
                    actionList = listOf(Action.keyCodeAction(KeyEvent.KEYCODE_ALT_LEFT))
                )
            )

            //imitate how modifier keys are sent on Android by also changing the metastate of the keyevent

            inputKeyEvent(
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.ACTION_DOWN,
                metaState = KeyEvent.META_CTRL_LEFT_ON + KeyEvent.META_CTRL_ON
            )
            inputKeyEvent(
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.ACTION_DOWN,
                metaState = KeyEvent.META_CTRL_LEFT_ON + KeyEvent.META_CTRL_ON + KeyEvent.META_SHIFT_LEFT_ON + KeyEvent.META_SHIFT_ON
            )
            inputKeyEvent(
                KeyEvent.KEYCODE_C,
                KeyEvent.ACTION_DOWN,
                metaState = KeyEvent.META_CTRL_LEFT_ON + KeyEvent.META_CTRL_ON + KeyEvent.META_SHIFT_LEFT_ON + KeyEvent.META_SHIFT_ON
            )

            inputKeyEvent(
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.ACTION_UP,
                metaState = KeyEvent.META_CTRL_LEFT_ON + KeyEvent.META_CTRL_ON + KeyEvent.META_SHIFT_LEFT_ON + KeyEvent.META_SHIFT_ON
            )
            inputKeyEvent(
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.ACTION_UP,
                metaState = KeyEvent.META_SHIFT_LEFT_ON + KeyEvent.META_SHIFT_ON
            )
            inputKeyEvent(KeyEvent.KEYCODE_C, KeyEvent.ACTION_UP)

            advanceUntilIdle()

            val imitatedKeyMetaState = imitateButtonPressTest.history.map { it.metaState }

            eventStream.printHistory()

            val expectedMetaState = listOf(
                KeyEvent.META_ALT_LEFT_ON + KeyEvent.META_ALT_ON + KeyEvent.META_SHIFT_LEFT_ON + KeyEvent.META_SHIFT_ON,
                0
            )

            assertThat(imitatedKeyMetaState, `is`(expectedMetaState))
        }

    @Test
    fun `2x key sequence trigger and 3x key sequence trigger with the last 2 keys being the same, trigger 3x key trigger, ignore the first 2x key trigger`() =
        coroutineScope.runBlockingTest {

            val firstTrigger = sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    deviceId = Trigger.Key.DEVICE_ID_ANY_DEVICE
                ),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP)
            )

            val secondTrigger = sequenceTrigger(
                Trigger.Key(KeyEvent.KEYCODE_HOME),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    deviceId = Trigger.Key.DEVICE_ID_ANY_DEVICE
                ),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP)
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, trigger = firstTrigger, actionList = listOf(TEST_ACTION)),
                KeyMap(1, trigger = secondTrigger, actionList = listOf(TEST_ACTION_2))
            )

            val performedActions = mutableSetOf<Action>()

            val observer = Observer<PerformAction> {
                performedActions.add(it.action)
            }

            delegate.performAction.observeForever(observer)

            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_HOME))
            mockTriggerKeyInput(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    deviceId = Trigger.Key.DEVICE_ID_ANY_DEVICE
                )
            )
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP))

            advanceUntilIdle()

            assertThat(performedActions, `is`(mutableSetOf(TEST_ACTION_2)))

            delegate.performAction.removeObserver(observer)
        }

    @Test
    fun `2x key long press parallel trigger with HOME or RECENTS keycode, trigger successfully, don't do normal action`() =
        coroutineScope.runBlockingTest {
            /*
            HOME
             */

            val keysHome = arrayOf(
                Trigger.Key(KeyEvent.KEYCODE_HOME, clickType = LONG_PRESS),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS)
            )

            delegate.keymapListCache = listOf(
                createValidKeymapFromTriggerKey(0, *keysHome, triggerMode = Trigger.PARALLEL)
            )

            val consumedHomeDown = inputKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, null)
            inputKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, null)

            advanceUntilIdle()

            inputKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, null)
            inputKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, null)

            assertEquals(true, consumedHomeDown)

            /*
            RECENTS
             */

            val keysRecents = arrayOf(
                Trigger.Key(KeyEvent.KEYCODE_APP_SWITCH, clickType = LONG_PRESS),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS)
            )

            delegate.keymapListCache = listOf(
                createValidKeymapFromTriggerKey(0, *keysRecents, triggerMode = Trigger.PARALLEL)
            )

            val consumedRecentsDown =
                inputKeyEvent(KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.ACTION_DOWN, null)
            inputKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, null)

            advanceUntilIdle()

            inputKeyEvent(KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.ACTION_UP, null)
            inputKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, null)

            assertEquals(true, consumedRecentsDown)
        }

    @Test
    fun shortPressTriggerDoublePressTrigger_holdDown_onlyDetectDoublePressTrigger() =
        coroutineScope.runBlockingTest {
            //given
            val shortPressTrigger =
                undefinedTrigger(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = SHORT_PRESS))
            val longPressTrigger = undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    clickType = DOUBLE_PRESS
                )
            )

            delegate.keymapListCache = listOf(
                KeyMap(0, shortPressTrigger, listOf(TEST_ACTION)),
                KeyMap(1, longPressTrigger, listOf(TEST_ACTION_2))
            )

            //when
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = DOUBLE_PRESS))
            advanceUntilIdle()

            //then
            //the first action performed shouldn't be the short press action
            assertEquals(TEST_ACTION_2, delegate.performAction.value?.action)

            /*
            rerun the test to see if the short press trigger action is performed correctly.
             */

            //when
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = SHORT_PRESS))
            advanceUntilIdle()

            //then
            val action = delegate.performAction.value?.action
            assertEquals(TEST_ACTION, action)
        }

    @Test
    fun shortPressTriggerLongPressTrigger_holdDown_onlyDetectLongPressTrigger() =
        coroutineScope.runBlockingTest {
            //GIVEN
            val shortPressTrigger =
                undefinedTrigger(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = SHORT_PRESS))
            val longPressTrigger =
                undefinedTrigger(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS))

            delegate.keymapListCache = listOf(
                KeyMap(0, shortPressTrigger, listOf(TEST_ACTION)),
                KeyMap(1, longPressTrigger, listOf(TEST_ACTION_2))
            )

            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS))
            advanceUntilIdle()

            //THEN

            //the first action performed shouldn't be the short press action
            assertEquals(TEST_ACTION_2, delegate.performAction.value?.action)

            //WHEN
            //rerun the test to see if the short press trigger action is performed correctly.
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = SHORT_PRESS))
            advanceUntilIdle()

            //THEN
            //the first action performed shouldn't be the short press action
            assertEquals(TEST_ACTION, delegate.performAction.value?.action)
        }

    @Test
    @Parameters(method = "params_repeatAction")
    fun parallelTrigger_holdDown_repeatAction10Times(description: String, trigger: Trigger) =
        coroutineScope.runBlockingTest {
            //given
            val action = Action(
                type = ActionType.SYSTEM_ACTION,
                data = SystemAction.VOLUME_UP,
                flags = Action.ACTION_FLAG_REPEAT
            )

            val keymap = KeyMap(0, trigger, actionList = listOf(action))
            delegate.keymapListCache = listOf(keymap)

            var repeatCount = 0

            //when
            withTimeout(2000) {
                trigger.keys.forEach {
                    inputKeyEvent(
                        it.keyCode,
                        KeyEvent.ACTION_DOWN,
                        deviceIdToDescriptor(it.deviceId)
                    )
                }

                //given
                while (repeatCount < 10) {
                    repeatCount++
                }
            }

            trigger.keys.forEach {
                inputKeyEvent(it.keyCode, KeyEvent.ACTION_UP, deviceIdToDescriptor(it.deviceId))
            }

            assertThat("Failed to repeat at least 10 times in 2 seconds", repeatCount >= 10)
        }

    fun params_repeatAction() = listOf(
        arrayOf(
            "long press multiple keys", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, clickType = LONG_PRESS)
            )
        ),
        arrayOf(
            "long press single key", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS)
            )
        ),
        arrayOf(
            "short press multiple keys", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = SHORT_PRESS),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, clickType = SHORT_PRESS)
            )
        ),
        arrayOf(
            "short press single key", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, clickType = SHORT_PRESS)
            )
        )
    )

    @Test
    @Parameters(method = "params_dualParallelTrigger_input2ndKey_dontConsumeUp")
    fun dualParallelTrigger_input2ndKey_dontConsumeUp(description: String, trigger: Trigger) {
        //given
        val keymap = KeyMap(0, trigger, actionList = listOf(TEST_ACTION))
        delegate.keymapListCache = listOf(keymap)

        runBlocking {
            //when
            trigger.keys[1].let {
                inputKeyEvent(it.keyCode, KeyEvent.ACTION_DOWN, deviceIdToDescriptor(it.deviceId))
            }

            trigger.keys[1].let {
                val consumed =
                    inputKeyEvent(it.keyCode, KeyEvent.ACTION_UP, deviceIdToDescriptor(it.deviceId))

                //then
                assertEquals(false, consumed)
            }
        }
    }

    fun params_dualParallelTrigger_input2ndKey_dontConsumeUp() = listOf(
        arrayOf(
            "long press", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, clickType = LONG_PRESS)
            )
        ),

        arrayOf(
            "short press", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = SHORT_PRESS),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, clickType = SHORT_PRESS)
            )
        )
    )

    @Test
    fun dualShortPressParallelTrigger_validInput_consumeUp() {
        //given
        val trigger = parallelTrigger(
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN),
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP)
        )

        val keymap = KeyMap(0, trigger, actionList = listOf(TEST_ACTION))
        delegate.keymapListCache = listOf(keymap)

        runBlocking {
            //when
            trigger.keys.forEach {
                inputKeyEvent(it.keyCode, KeyEvent.ACTION_DOWN, deviceIdToDescriptor(it.deviceId))
            }

            var consumedUpCount = 0

            trigger.keys.forEach {
                val consumed =
                    inputKeyEvent(it.keyCode, KeyEvent.ACTION_UP, deviceIdToDescriptor(it.deviceId))

                if (consumed) {
                    consumedUpCount += 1
                }
            }

            //then
            assertEquals(2, consumedUpCount)
        }
    }

    @Test
    fun dualLongPressParallelTrigger_validInput_consumeUp() = coroutineScope.runBlockingTest {
        //given
        val trigger = parallelTrigger(
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS),
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, clickType = LONG_PRESS)
        )

        val keymap = KeyMap(0, trigger, actionList = listOf(TEST_ACTION))
        delegate.keymapListCache = listOf(keymap)

        //when
        trigger.keys.forEach {
            inputKeyEvent(it.keyCode, KeyEvent.ACTION_DOWN, deviceIdToDescriptor(it.deviceId))
        }

        advanceUntilIdle()

        var consumedUpCount = 0

        trigger.keys.forEach {
            val consumed =
                inputKeyEvent(it.keyCode, KeyEvent.ACTION_UP, deviceIdToDescriptor(it.deviceId))

            if (consumed) {
                consumedUpCount += 1
            }
        }

        //then
        assertEquals(2, consumedUpCount)
    }

    @Test
    fun keymappedToLongPressAndDoublePress_invalidLongPress_imitateOnce() =
        coroutineScope.runBlockingTest {
            //given
            val longPressKeymap = createValidKeymapFromTriggerKey(
                0,
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS)
            )

            val doublePressKeymap = createValidKeymapFromTriggerKey(
                1,
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = DOUBLE_PRESS)
            )

            delegate.keymapListCache = listOf(longPressKeymap, doublePressKeymap)

            //when

            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN))

            //then
            assertNull(delegate.imitateButtonPress.value)

            advanceUntilIdle()

            assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, delegate.imitateButtonPress.value?.keyCode)
        }

    @Test
    fun keymappedToSingleShortPressAndLongPress_validShortPress_onlyPerformActionDontImitateKey() {
        //given
        val shortPressKeymap = createValidKeymapFromTriggerKey(
            0,
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN)
        )

        val longPressKeymap = createValidKeymapFromTriggerKey(
            1,
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS)
        )

        delegate.keymapListCache = listOf(shortPressKeymap, longPressKeymap)

        //when

        runBlocking {
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN))
        }

        //then
        assertEquals(TEST_ACTION, delegate.performAction.value?.action)
        assertNull(delegate.imitateButtonPress.value)
    }

    @Test
    fun keymappedToShortPressAndDoublePress_validShortPress_onlyPerformActionDoNotImitateKey() =
        coroutineScope.runBlockingTest {
            //given
            val shortPressKeymap = createValidKeymapFromTriggerKey(
                0,
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN)
            )

            val doublePressKeymap = createValidKeymapFromTriggerKey(
                1,
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = DOUBLE_PRESS)
            )

            delegate.keymapListCache = listOf(shortPressKeymap, doublePressKeymap)

            //when

            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN))

            //then
            assertEquals(TEST_ACTION, delegate.performAction.value?.action)

            //wait for the double press to try and imitate the key.
            advanceUntilIdle()
            assertNull(delegate.imitateButtonPress.value)
        }

    @Test
    fun singleKeyTriggerAndShortPressParallelTriggerWithSameInitialKey_validSingleKeyTriggerInput_onlyPerformActionDontImitateKey() =
        coroutineScope.runBlockingTest {
            //given
            val singleKeyKeymap =
                createValidKeymapFromTriggerKey(0, Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN))
            val parallelTriggerKeymap = createValidKeymapFromTriggerKey(
                1,
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP),
                triggerMode = Trigger.PARALLEL
            )

            delegate.keymapListCache = listOf(singleKeyKeymap, parallelTriggerKeymap)

            //when
            mockTriggerKeyInput(Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN))

            //then
            assertNull(delegate.imitateButtonPress.value)
            assertEquals(TEST_ACTION, delegate.performAction.value?.action)
        }

    @Test
    fun longPressSequenceTrigger_invalidLongPress_keyImitated() {
        val trigger = sequenceTrigger(
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS),
            Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP)
        )

        val keymap = createValidKeymapFromTriggerKey(0, *trigger.keys.toTypedArray())
        delegate.keymapListCache = listOf(keymap)

        runBlocking {
            mockTriggerKeyInput(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, clickType = LONG_PRESS),
                100
            )
        }

        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, delegate.imitateButtonPress.value?.keyCode)
    }

    @Test
    @Parameters(method = "params_multipleActionsPerformed")
    fun validInput_multipleActionsPerformed(description: String, trigger: Trigger) =
        coroutineScope.runBlockingTest {
            //GIVEN
            val keymap = KeyMap(0, trigger, TEST_ACTIONS.toList())
            delegate.keymapListCache = listOf(keymap)

            val performedActions = mutableSetOf<Action>()

            delegate.performAction.observeForever {
                performedActions.add(it.action)
            }

            //WHEN
            if (keymap.trigger.mode == Trigger.PARALLEL) {
                mockParallelTriggerKeys(*keymap.trigger.keys.toTypedArray())
            } else {
                keymap.trigger.keys.forEach {
                    mockTriggerKeyInput(it)
                }
            }

            //THEN
            assertEquals(TEST_ACTIONS, performedActions)
        }

    fun params_multipleActionsPerformed() = listOf(
        arrayOf(
            "undefined",
            undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE
                )
            )
        ),
        arrayOf(
            "sequence",
            sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE
                )
            )
        ),
        arrayOf(
            "parallel", parallelTrigger(
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_DOWN, Trigger.Key.DEVICE_ID_THIS_DEVICE),
                Trigger.Key(KeyEvent.KEYCODE_VOLUME_UP, Trigger.Key.DEVICE_ID_THIS_DEVICE)
            )
        )
    )

    @Test
    @Parameters(method = "params_allTriggerKeyCombinations")
    @TestCaseName("{0}")
    fun invalidInput_downNotConsumed(description: String, keymap: KeyMap) {
        //GIVEN
        delegate.keymapListCache = listOf(keymap)

        //WHEN
        var consumedCount = 0

        keymap.trigger.keys.forEach {
            val consumed =
                inputKeyEvent(999, KeyEvent.ACTION_DOWN, deviceIdToDescriptor(it.deviceId))

            if (consumed) {
                consumedCount++
            }
        }

        //THEN
        assertEquals(0, consumedCount)
    }

    @Test
    @Parameters(method = "params_allTriggerKeyCombinations")
    @TestCaseName("{0}")
    fun validInput_downConsumed(description: String, keymap: KeyMap) {
        //GIVEN
        delegate.keymapListCache = listOf(keymap)

        var consumedCount = 0

        keymap.trigger.keys.forEach {
            val consumed =
                inputKeyEvent(it.keyCode, KeyEvent.ACTION_DOWN, deviceIdToDescriptor(it.deviceId))

            if (consumed) {
                consumedCount++
            }
        }

        assertEquals(keymap.trigger.keys.size, consumedCount)
    }

    @Test
    @Parameters(method = "params_allTriggerKeyCombinationsDontConsume")
    @TestCaseName("{0}")
    fun validInput_dontConsumeFlag_dontConsumeDown(description: String, keymap: KeyMap) {
        delegate.keymapListCache = listOf(keymap)

        var consumedCount = 0

        keymap.trigger.keys.forEach {
            val consumed =
                inputKeyEvent(it.keyCode, KeyEvent.ACTION_DOWN, deviceIdToDescriptor(it.deviceId))

            if (consumed) {
                consumedCount++
            }
        }

        assertEquals(0, consumedCount)
    }

    fun params_allTriggerKeyCombinationsDontConsume(): List<Array<Any>> {
        val triggerAndDescriptions = listOf(
            "undefined single short-press this-device, dont consume" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),
            "undefined single long-press this-device, dont consume" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),
            "undefined single double-press this-device, dont consume" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "undefined single short-press any-device, dont consume" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),
            "undefined single long-press any-device, dont consume" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),
            "undefined single double-press any-device, dont consume" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple short-press this-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple long-press this-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple double-press this-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple mix this-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple mix external-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple short-press mixed-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple long-press mixed-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple double-press mixed-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple mix mixed-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "sequence multiple mix mixed-device, dont consume" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = DOUBLE_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "parallel multiple short-press this-device, dont consume" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "parallel multiple long-press this-device, dont consume" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "parallel multiple short-press external-device, dont consume" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "parallel multiple long-press external-device, dont consume" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "parallel multiple short-press mix-device, dont consume" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = SHORT_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            ),

            "parallel multiple long-press mix-device, dont consume" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS,
                    flags = FLAG_DO_NOT_CONSUME_KEY_EVENT
                )
            )
        )

        return triggerAndDescriptions.mapIndexed { i, triggerAndDescription ->
            arrayOf(
                triggerAndDescription.first,
                KeyMap(i.toLong(), triggerAndDescription.second, listOf(TEST_ACTION))
            )
        }
    }

    fun params_allTriggerKeyCombinations(): List<Array<Any>> {
        val triggerAndDescriptions = listOf(
            "undefined single short-press this-device" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE
                )
            ),
            "undefined single long-press this-device" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                )
            ),
            "undefined single double-press this-device" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                )
            ),

            "undefined single short-press any-device" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE
                )
            ),
            "undefined single long-press any-device" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS
                )
            ),
            "undefined single double-press any-device" to undefinedTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = DOUBLE_PRESS
                )
            ),

            "sequence multiple short-press this-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                )
            ),
            "sequence multiple long-press this-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                )
            ),
            "sequence multiple double-press this-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                )
            ),
            "sequence multiple mix this-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                )
            ),
            "sequence multiple mix external-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(KeyEvent.KEYCODE_A, FAKE_KEYBOARD_DESCRIPTOR, clickType = DOUBLE_PRESS)
            ),

            "sequence multiple short-press mixed-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                )
            ),
            "sequence multiple long-press mixed-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                )
            ),
            "sequence multiple double-press mixed-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = DOUBLE_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = DOUBLE_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                )
            ),
            "sequence multiple mix mixed-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = DOUBLE_PRESS
                )
            ),
            "sequence multiple mix mixed-device" to sequenceTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(KeyEvent.KEYCODE_A, FAKE_KEYBOARD_DESCRIPTOR, clickType = DOUBLE_PRESS)
            ),

            "parallel multiple short-press this-device" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                )
            ),
            "parallel multiple long-press this-device" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_A,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                )
            ),
            "parallel multiple short-press external-device" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_KEYBOARD_DESCRIPTOR,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(KeyEvent.KEYCODE_A, FAKE_HEADPHONE_DESCRIPTOR, clickType = SHORT_PRESS)
            ),
            "parallel multiple long-press external-device" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    FAKE_HEADPHONE_DESCRIPTOR,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(KeyEvent.KEYCODE_A, FAKE_HEADPHONE_DESCRIPTOR, clickType = LONG_PRESS)
            ),
            "parallel multiple short-press mix-device" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = SHORT_PRESS
                ),
                Trigger.Key(KeyEvent.KEYCODE_A, FAKE_KEYBOARD_DESCRIPTOR, clickType = SHORT_PRESS)
            ),
            "parallel multiple long-press mix-device" to parallelTrigger(
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    Trigger.Key.DEVICE_ID_THIS_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    Trigger.Key.DEVICE_ID_ANY_DEVICE,
                    clickType = LONG_PRESS
                ),
                Trigger.Key(KeyEvent.KEYCODE_A, FAKE_KEYBOARD_DESCRIPTOR, clickType = LONG_PRESS)
            )
        )

        return triggerAndDescriptions.mapIndexed { i, triggerAndDescription ->
            arrayOf(
                triggerAndDescription.first,
                KeyMap(i.toLong(), triggerAndDescription.second, listOf(TEST_ACTION))
            )
        }
    }

    @Test
    @Parameters(method = "params_allTriggerKeyCombinations")
    @TestCaseName("{0}")
    fun validInput_actionPerformed(description: String, keymap: KeyMap) =
        coroutineScope.runBlockingTest {
            //GIVEN
            delegate.reset()
            delegate.keymapListCache = listOf(keymap)

            if (KeymapDetectionDelegate.performActionOnDown(
                    keymap.trigger.keys,
                    keymap.trigger.mode
                )
            ) {
                //WHEN
                mockParallelTriggerKeys(*keymap.trigger.keys.toTypedArray())
                advanceUntilIdle()

                //THEN
                val value = delegate.performAction.value

                assertThat(value?.action, `is`(TEST_ACTION))
            } else {
                //WHEN
                keymap.trigger.keys.forEach {
                    mockTriggerKeyInput(it)
                }

                advanceUntilIdle()

                //THEN
                val action = delegate.performAction.value?.action
                assertThat(action, `is`(TEST_ACTION))
            }
        }

    private suspend fun mockTriggerKeyInput(key: Trigger.Key, delay: Long? = null) {
        val deviceDescriptor = deviceIdToDescriptor(key.deviceId)
        val pressDuration: Long = delay ?: when (key.clickType) {
            LONG_PRESS -> LONG_PRESS_DELAY + 100L
            else -> 50L
        }

        inputKeyEvent(key.keyCode, KeyEvent.ACTION_DOWN, deviceDescriptor)

        when (key.clickType) {
            SHORT_PRESS -> {
                delay(pressDuration)
                inputKeyEvent(key.keyCode, KeyEvent.ACTION_UP, deviceDescriptor)
            }

            LONG_PRESS -> {
                delay(pressDuration)
                inputKeyEvent(key.keyCode, KeyEvent.ACTION_UP, deviceDescriptor)
            }

            DOUBLE_PRESS -> {
                delay(pressDuration)
                inputKeyEvent(key.keyCode, KeyEvent.ACTION_UP, deviceDescriptor)
                delay(pressDuration)

                inputKeyEvent(key.keyCode, KeyEvent.ACTION_DOWN, deviceDescriptor)
                delay(pressDuration)
                inputKeyEvent(key.keyCode, KeyEvent.ACTION_UP, deviceDescriptor)
            }
        }
    }

    private fun inputKeyEvent(
        keyCode: Int,
        action: Int,
        deviceDescriptor: String? = null,
        metaState: Int? = null,
        deviceId: Int = 0,
        scanCode: Int = 0
    ) = delegate.onKeyEvent(
        keyCode,
        action,
        deviceDescriptor ?: "",
        isExternal = deviceDescriptor != null,
        metaState = metaState ?: 0,
        deviceId,
        scanCode
    )

    private suspend fun mockParallelTriggerKeys(
        vararg key: Trigger.Key,
        delay: Long? = null
    ) {

        key.forEach {
            val deviceDescriptor = deviceIdToDescriptor(it.deviceId)

            inputKeyEvent(it.keyCode, KeyEvent.ACTION_DOWN, deviceDescriptor)
        }

        if (delay != null) {
            delay(delay)
        } else {
            when (key[0].clickType) {
                SHORT_PRESS -> delay(50)
                LONG_PRESS -> delay(LONG_PRESS_DELAY + 100L)
            }
        }

        key.forEach {
            val deviceDescriptor = deviceIdToDescriptor(it.deviceId)

            inputKeyEvent(it.keyCode, KeyEvent.ACTION_UP, deviceDescriptor)
        }
    }

    private fun deviceIdToDescriptor(deviceId: String): String? {
        return when (deviceId) {
            Trigger.Key.DEVICE_ID_THIS_DEVICE -> null
            Trigger.Key.DEVICE_ID_ANY_DEVICE -> {
                val randomInt = Random.nextInt(-1, FAKE_DESCRIPTORS.lastIndex)

                if (randomInt == -1) {
                    ""
                } else {
                    FAKE_DESCRIPTORS[randomInt]
                }
            }
            else -> deviceId
        }
    }

    private fun createValidKeymapFromTriggerKey(
        id: Long,
        vararg key: Trigger.Key,
        triggerMode: Int = Trigger.SEQUENCE
    ) =
        KeyMap(
            id,
            Trigger(keys = key.toList(), mode = triggerMode),
            actionList = listOf(TEST_ACTION)
        )
}