use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::OnceLock,
};

// The process owns one runtime, not one database. JNI never sees a connection.
fn compile(input: &str) -> Result<String, String> {
    static RUNTIME: OnceLock<Result<tokio::runtime::Runtime, String>> = OnceLock::new();
    let runtime = RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_stack_size(16 * 1024 * 1024)
            .enable_all()
            .build()
            .map_err(|e| e.to_string())
    });
    let runtime = runtime.as_ref().map_err(Clone::clone)?;
    let input = input.to_owned();
    // Java threads can have very small native stacks. Keep recursive parser and
    // DataFusion planning on our bounded worker pool, never on the JVM stack.
    runtime
        .block_on(runtime.spawn(async move { orchiddb::compiler::compile_json(&input).await }))
        .map_err(|_| "compiler worker failed; no SQL was executed".to_string())?
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_orchiddb_internal_NativeBridge_compileJson(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<_, String> {
        let input: String = env.get_string(&input).map_err(|e| e.to_string())?.into();
        let output = compile(&input)?;
        env.new_string(output)
            .map(|s| s.into_raw())
            .map_err(|e| e.to_string())
    }));
    match outcome {
        Ok(Ok(s)) => s,
        result => {
            let message = match result {
                Ok(Err(e)) => e,
                _ => "native compiler panicked; no SQL was executed".into(),
            };
            let _ = env.throw_new("io/orchiddb/PlanningException", message);
            std::ptr::null_mut()
        }
    }
}
