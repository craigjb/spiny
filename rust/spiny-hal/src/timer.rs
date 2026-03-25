/// Generates an [`embassy_time_driver::Driver`] backed by a SpinyTimer peripheral.
///
/// The timer must have `num_compares >= 2`:
/// - `compare0` tracks the half-period point (extends N-bit counter to 64-bit)
/// - `compare1` is the alarm for the timer queue
///
/// # Parameters
///
/// | Parameter | Description |
/// |-----------|-------------|
/// | `pac` | PAC crate name (e.g., `blinky_pac`) |
/// | `peripheral` | Timer peripheral type in the PAC (e.g., `Timer`) |
/// | `timer_width` | Counter width in bits (16 or 32) |
/// | `num_compares` | Number of compare channels (must be &ge; 2) |
/// | `cpu_freq_hz` | System clock frequency in Hz |
///
/// # Generated Items
///
/// - `MachineTimer` — interrupt handler (via `#[export_name]`)
/// - [`init`] — call once at startup within a [`critical_section::CriticalSection`]
///
/// # Example
///
/// ```ignore
/// spiny_hal::timer_hal! {
///     pac: blinky_pac,
///     peripheral: Timer,
///     timer_width: 32,
///     num_compares: 2,
///     cpu_freq_hz: 100_000_000,
/// }
/// ```
#[macro_export]
macro_rules! timer_hal {
    (
        pac: $pac:ident,
        peripheral: $timer:ident,
        timer_width: $tw:expr,
        num_compares: $nc:expr,
        cpu_freq_hz: $cpu_freq:expr $(,)?
    ) => {
        use core::cell::RefCell;
        use core::sync::atomic::{compiler_fence, AtomicU32, Ordering};
        use critical_section::Mutex;
        use embassy_time_driver::{Driver, TICK_HZ};
        use embassy_time_queue_utils::Queue;

        const TIMER_WIDTH: u32 = $tw;
        const HALF_PERIOD: u32 = 1u32 << (TIMER_WIDTH - 1);
        const SOON_THRESHOLD: u64 = 3u64 << (TIMER_WIDTH - 2);

        const _: () = assert!($nc >= 2, "timer_hal! requires num_compares >= 2");

        // Clock timekeeping uses "periods" of 2^(timer_width-1) ticks.
        // One full counter cycle = 2 periods. A `period` counter is
        // maintained in parallel:
        //   - incremented on overflow (counter wraps to 0)
        //   - incremented at half-period (counter reaches HALF_PERIOD)
        //
        // When `period` is even, counter is in [0, HALF_PERIOD).
        // When `period` is odd, counter is in [HALF_PERIOD, 2^timer_width).
        //
        // This allows race-free reading of 64-bit time from the
        // 32-bit period + N-bit counter pair.
        fn calc_now(period: u32, counter: u32) -> u64 {
            ((period as u64) << (TIMER_WIDTH - 1))
                + ((counter ^ ((period & 1) * HALF_PERIOD)) as u64)
        }

        struct SpinyTimeDriver {
            period: AtomicU32,
            queue: Mutex<RefCell<Queue>>,
        }

        embassy_time_driver::time_driver_impl!(static DRIVER: SpinyTimeDriver = SpinyTimeDriver {
            period: AtomicU32::new(0),
            queue: Mutex::new(RefCell::new(Queue::new())),
        });

        #[export_name = "MachineTimer"]
        fn timer_interrupt() {
            DRIVER.on_interrupt()
        }

        impl SpinyTimeDriver {
            fn init(&'static self, _cs: critical_section::CriticalSection) {
                let timer = unsafe { $pac::$timer::steal() };

                let psc = ($cpu_freq as u64) / TICK_HZ - 1;
                timer.prescale().write(|w| unsafe { w.value().bits(psc as _) });

                // Half-period compare for 64-bit time extension
                timer.compare0().write(|w| unsafe { w.value().bits(HALF_PERIOD) });

                // Unmask overflow and compare0 (half-period)
                // Mask alarm (compare1)
                timer.interrupt_mask().write(|w| {
                    w.overflow_mask()
                        .clear_bit()
                        .compare0mask()
                        .clear_bit()
                        .compare1mask()
                        .set_bit()
                });

                // Clear all pending interrupt status
                timer.interrupt_status().write(|w| {
                    w.overflow_status()
                        .clear_bit_by_one()
                        .compare0status()
                        .clear_bit_by_one()
                        .compare1status()
                        .clear_bit_by_one()
                });

                // Enable timer with interrupts
                timer
                    .control()
                    .write(|w| w.enable().set_bit().interrupt_enable().set_bit());

                unsafe {
                    riscv::register::mie::set_mtimer();
                }
            }

            fn on_interrupt(&self) {
                critical_section::with(|cs| {
                    let timer = unsafe { $pac::$timer::steal() };
                    let status = timer.interrupt_status().read();

                    // Clear all interrupt status
                    timer.interrupt_status().write(|w| {
                        w.overflow_status()
                            .clear_bit_by_one()
                            .compare0status()
                            .clear_bit_by_one()
                            .compare1status()
                            .clear_bit_by_one()
                    });

                    // Overflow and half-period/compare0 each advance the
                    // period counter
                    if status.overflow_status().bit_is_set() {
                        self.period.store(
                            self.period.load(Ordering::Relaxed) + 1,
                            Ordering::Relaxed,
                        );
                    }
                    if status.compare0status().bit_is_set() {
                        self.period.store(
                            self.period.load(Ordering::Relaxed) + 1,
                            Ordering::Relaxed,
                        );
                    }

                    // Process queue: dequeue expired, set next alarm
                    let mut queue = self.queue.borrow(cs).borrow_mut();
                    loop {
                        let next = queue.next_expiration(self.now());
                        if self.set_alarm_hw(next) {
                            break;
                        }
                    }
                });
            }

            /// Set hardware compare1 alarm. Returns true if alarm is set
            /// for the future (or no alarm needed). Returns false if the
            /// timestamp has already passed (caller should dequeue and retry).
            fn set_alarm_hw(&self, at: u64) -> bool {
                let timer = unsafe { $pac::$timer::steal() };

                if at == u64::MAX {
                    // No pending alarms, mask the compare interrupt
                    timer
                        .interrupt_mask()
                        .modify(|_, w| w.compare1mask().set_bit());
                    return true;
                }

                let t = self.now();
                if at <= t {
                    return false;
                }

                // Write the compare value
                timer
                    .compare1()
                    .write(|w| unsafe { w.value().bits(at as u32) });

                // Enable alarm if it's coming soon, otherwise next_period
                // will enable it when the period advances
                let diff = at - t;
                timer.interrupt_mask().modify(|_, w| {
                    w.compare1mask().bit(!(diff < SOON_THRESHOLD))
                });

                // Re-check for race condition
                let t = self.now();
                if at <= t {
                    // Alarm time passed between setting and checking
                    timer
                        .interrupt_mask()
                        .modify(|_, w| w.compare1mask().set_bit());
                    return false;
                }

                true
            }
        }

        impl Driver for SpinyTimeDriver {
            fn now(&self) -> u64 {
                let timer = unsafe { $pac::$timer::steal() };

                let period = self.period.load(Ordering::Relaxed);
                compiler_fence(Ordering::Acquire);
                let counter = timer.counter().read().counter().bits();
                calc_now(period, counter)
            }

            fn schedule_wake(&self, at: u64, waker: &core::task::Waker) {
                critical_section::with(|cs| {
                    let mut queue = self.queue.borrow(cs).borrow_mut();
                    if queue.schedule_wake(at, waker) {
                        loop {
                            let next = queue.next_expiration(self.now());
                            if self.set_alarm_hw(next) {
                                break;
                            }
                        }
                    }
                });
            }
        }

        /// Initialize the time driver.
        ///
        /// Must be called once at startup from within a critical section.
        /// After this, Embassy time and the MachineTimer interrupt are active.
        pub fn init(cs: critical_section::CriticalSection) {
            DRIVER.init(cs)
        }
    };
}
