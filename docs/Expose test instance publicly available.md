Discussion with ChatGPT 5.4 Thinking:

Check this:

[DONE 2026-03-04] Expose test instance publicly available - part 2
From https://github.com/marcingurbisz/flowlite/blob/main/memory/TODO.md
Maybe you will find better option?

I’m checking the repo note against what those hosts actually support now, and I’ll compare Render with a few likely alternatives for a small public Spring/JVM test instance.

I’ve confirmed the current repo note and I’m now checking whether the “Render is the best default” decision still holds against current free or near-free hosts for Spring Boot.

Thought for 41s

Mostly good, but I would update the decision note.

Your current conclusion is defensible, especially for a “public demo with GitHub-connected deploys,” because Render still supports free web services and GitHub Pages is still static-only, so Pages is not suitable for a JVM/Spring backend. Render’s free web services also still spin down after 15 minutes of inactivity and can take about a minute to wake up, which matches your “idle sleep / cold-start” note.

What I would change is this part:

“Selected Render free web service as the default provider…”

As of now, Render is a reasonable default, but not obviously the best one anymore. Koyeb is a strong alternative for exactly this use case: it supports GitHub-based deploys, Spring Boot / Docker deployments, offers one free web service, and its free instance scales to zero only after 1 hour of no traffic rather than 15 minutes. Koyeb also says cold starts from deep sleep are typically 1–5 seconds. The trade-off is that Koyeb requires a credit card even for free usage.

Railway is less attractive as the default “free public test instance” choice now, because its docs describe the Free plan as $1 of free credit per month, so it is no longer the simple always-available hobby default many people expect from “free tier.” It does support Spring Boot and GitHub deploys with a generated public domain, though.

So my recommendation would be:

Best low-friction default: Render
Reason: simple mental model, free web service, GitHub-connected deployment, widely used for hobby/demo exposure. Trade-off: the 15-minute sleep and roughly 1-minute wake-up is pretty noticeable.

Potentially better default for UX: Koyeb
Reason: also works well with GitHub + Docker/Spring Boot, and the sleep/cold-start behavior looks much friendlier for a public demo. Trade-off: credit-card requirement, only one free web service, and free instance is small.

For your TODO/README wording, I’d replace the decision summary with this:

GitHub Pages is static-only and not suitable for hosting this JVM/Spring service. GitHub remains a good choice for repository hosting and CI/CD-triggered deployments. Render is a reasonable default for zero-cost public demo exposure, but Koyeb is also a strong option and may provide a better demo experience due to less aggressive idle behavior and faster wake-up, at the cost of requiring credit-card verification.

Also, your repo still shows the earlier tunnel-based item (localhost.run) in TODO.md, so if this new “part 2” work is only in your local branch and not yet merged, that’s worth noting explicitly in the changelog/history to avoid confusion.

My verdict: not wrong, but I’d no longer present Render as the clear best option. I’d present it as the safest default, with Koyeb as the “better demo UX” alternative.

If you want, I can rewrite that TODO/README section into cleaner final wording.

Worth to go for GraalVM or similar?
Start time on render will be mostly from machine startup or from JVM/app startup?
Periodic network calls from app to itself will prevent sleep or this must be external trafic?

I’m checking three things: whether GraalVM meaningfully helps this specific cold-start problem, what portion of Render startup is platform wake-up versus JVM boot, and whether self-pings actually keep a free service awake.

Thought for 26s

Probably not worth it just for Render free-tier cold starts, unless your Spring app itself is unusually heavy. Spring Boot’s GraalVM native images do generally start faster and use less memory than JVM builds, so they can improve application startup. But Render’s free web services spin down after 15 minutes without inbound traffic, and waking them back up takes about one minute. That means on Render free, a big chunk of the bad first-hit latency is often the platform wake-up, not just JVM bootstrap.

So the practical split is:

Render wake-up: restarting the spun-down free service, documented at about 1 minute.

Your app startup: JVM boot + Spring context init, where GraalVM/native can help.

If your app currently starts on JVM in, say, 2–5 seconds, GraalVM probably won’t change the user-visible Render-free experience much, because the platform wake-up dominates. If your app takes 20–40+ seconds to become healthy, then GraalVM could still noticeably reduce the total cold-start pain. That last part is an inference from Render’s documented wake-up behavior plus Spring’s documented faster native startup.

On the self-ping question: Render says a free service stays awake while receiving inbound HTTP requests or WebSocket messages. So external traffic is not strictly required in the sense that a request to your service’s public onrender.com URL should count as inbound traffic even if it originated from your own code. But a pure internal call to localhost or an in-process timer obviously would not count as inbound traffic through Render’s edge/load balancer.

The important catch is this: once the service has already spun down, your app is no longer running, so an internal scheduler inside the app cannot fire and wake itself back up. In other words, app-to-itself keepalive only works while the app is already awake. To revive a sleeping free service, the triggering request has to come from outside the stopped process. That conclusion is an inference from Render’s “spins down” behavior and the fact that wake-up happens on the next inbound request.

There is another caveat: Render explicitly warns that it may suspend a free service that generates an unusually high volume of service-initiated traffic over the public internet. So a self-ping loop against your public URL is not something I’d treat as a clean, recommended architecture, even if it may work technically. Also, keeping the service awake longer consumes your monthly free instance hours, because those hours are counted while the service is running.

My practical recommendation:

For a demo/test instance on Render free: stay on normal JVM first, and make startup lean.

Consider GraalVM native only if your Spring app startup is genuinely slow on its own.

If fast first response matters more than zero cost, upgrading off Render free or using a host with gentler scale-to-zero behavior will help more than GraalVM alone. Render itself recommends paid instances to avoid spin-down.

Rule of thumb:

Need cheapest/simple: JVM on Render is fine.

Need better cold-start UX on free: change hosting first.

Need every second squeezed out: then evaluate GraalVM.

I can also help you estimate, from your current Spring stack, whether native image is likely to be painless or annoying for FlowLite.

Also, keeping the service awake longer consumes your monthly free instance hours, because those hours are counted while the service is running. - how many free hours I have monthly?
Thought for a few seconds

750 free instance hours per workspace per calendar month. They reset every month and do not roll over. If you use them up, Render suspends your free web services until the next month begins.

In practice:

1 always-on free service for a full 31-day month is about 744 hours, so it usually fits.

2 always-on free services would be about 1488 hours, so they would not fit in the free pool. That’s just arithmetic from the 750-hour limit.

The important detail is that a free web service consumes hours while it is running. A spun-down service does not consume those hours.

So for your keepalive idea:

keeping one service awake 24/7 is roughly okay,

keeping several free services awake continuously will burn through the shared monthly pool much faster.

If you want, I can also calculate your likely monthly hour usage for 1 service with realistic sleep/wake patterns.