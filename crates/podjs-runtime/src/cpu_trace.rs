//! Bounded numeric CPU telemetry over an already authorized cloud log channel.
//! No promises, guest strings, file names, UI or new permissions. Scope tokens
//! are guest-local correlation only and are never serialized or logged.
use crate::background::{Cancellation, ServiceReply, ServiceRequest};
use rquickjs::{Ctx, Function};
use std::{
    sync::{mpsc::Receiver, Arc, Mutex},
    time::{Duration, Instant},
};
type Dispatch = Arc<dyn Fn(ServiceRequest) -> Receiver<ServiceReply> + Send + Sync>;
const INTERVAL: Duration = Duration::from_secs(5);
#[derive(Clone, Copy)]
struct Work {
    token: u32,
    stage: u8,
    scale: u8,
    band: u8,
    started: Instant,
}
struct State {
    calls: u16,
    last: [Option<(Instant, u8)>; 64],
    active: Vec<Work>,
}
#[derive(Clone)]
pub(crate) struct Trace(Arc<Inner>);
struct Inner {
    dispatch: Dispatch,
    route: String,
    app: String,
    task: String,
    deadline: Instant,
    cancel: Cancellation,
    state: Mutex<State>,
}
impl Trace {
    pub(crate) fn new(
        dispatch: Dispatch,
        route: String,
        app: String,
        task: String,
        deadline: Instant,
        cancel: Cancellation,
    ) -> Self {
        Self(Arc::new(Inner {
            dispatch,
            route,
            app,
            task,
            deadline,
            cancel,
            state: Mutex::new(State {
                calls: 0,
                last: [None; 64],
                active: Vec::new(),
            }),
        }))
    }
    fn emit(&self, work: Work, elapsed: u64, outcome: u8) {
        let now = Instant::now();
        if self.0.cancel.is_cancelled() || now >= self.0.deadline {
            return;
        }
        {
            let Ok(mut state) = self.0.state.lock() else {
                return;
            };
            if state.calls >= 4096 {
                return;
            }
            if let Some((at, kind)) = state.last[work.stage as usize] {
                if now.duration_since(at) < INTERVAL
                    && !((kind == 0 || kind == 1) && (outcome == 2 || outcome == 3))
                {
                    return;
                }
            }
            state.last[work.stage as usize] = Some((now, outcome));
            state.calls += 1;
        }
        let scale = ["bytes", "chars", "items"][work.scale as usize];
        let status = ["started", "progress", "complete", "failed"][outcome as usize];
        let metric = serde_json::json!({"event":"cpu-work","cpuStageId":work.stage,"scale":scale,"sizeClass":work.band,"elapsedMillis":elapsed.min(300000),"outcome":status});
        let args = serde_json::json!({"method":"log.append","args":{"path":"app_log.txt","text":format!(" [I] CloudCPU {metric}\n"),"maxBytes":5*1024*1024}});
        // Do not occupy any of the guest's 16 pending promise slots. The existing
        // worker consumes this closed log frame while the guest is still computing.
        drop((self.0.dispatch)(ServiceRequest {
            app_id: self.0.app.clone(),
            task_id: self.0.task.clone(),
            method: self.0.route.clone(),
            args_json: args.to_string(),
            deadline: self.0.deadline,
            cancellation: self.0.cancel.clone(),
        }));
    }
    fn record(
        &self,
        stage: f64,
        scale: f64,
        band: f64,
        elapsed: f64,
        outcome: f64,
        token: f64,
    ) -> bool {
        let integer = |n: f64, max: f64| n.is_finite() && n >= 0.0 && n <= max && n == n.floor();
        if !integer(stage, 63.0)
            || !integer(scale, 2.0)
            || !integer(band, 5.0)
            || !integer(elapsed, 300000.0)
            || !integer(outcome, 3.0)
            || !integer(token, u32::MAX as f64)
            || self.0.cancel.is_cancelled()
            || Instant::now() >= self.0.deadline
        {
            return false;
        }
        let work = Work {
            token: token as u32,
            stage: stage as u8,
            scale: scale as u8,
            band: band as u8,
            started: Instant::now(),
        };
        {
            let Ok(mut state) = self.0.state.lock() else {
                return false;
            };
            let at = state
                .active
                .iter()
                .position(|w| w.token == work.token && w.stage == work.stage);
            if outcome == 0.0 {
                if let Some(at) = at {
                    state.active.remove(at);
                }
                if state.active.len() < 32 {
                    state.active.push(work);
                }
            } else if outcome >= 2.0 {
                if let Some(at) = at {
                    state.active.remove(at);
                }
            }
        }
        self.emit(work, elapsed as u64, outcome as u8);
        true
    }
    /// Called at QuickJS interrupt boundaries, including a long synchronous parse,
    /// sort or digest that never reaches a JS yield or promise continuation.
    pub(crate) fn sample(&self) {
        let work = {
            let Ok(state) = self.0.state.lock() else {
                return;
            };
            state.active.last().copied()
        };
        if let Some(work) = work {
            self.emit(
                work,
                work.started.elapsed().as_millis().min(300000) as u64,
                1,
            );
        }
    }
    pub(crate) fn mount<'js>(&self, ctx: Ctx<'js>) -> rquickjs::Result<()> {
        let trace = self.clone();
        ctx.globals().set(
            "__podCpuTrace",
            Function::new(
                ctx.clone(),
                move |stage: f64, scale: f64, band: f64, elapsed: f64, outcome: f64, token: f64| {
                    trace.record(stage, scale, band, elapsed, outcome, token)
                },
            )?,
        )
    }
}
