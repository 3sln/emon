// PARALLEL EXECUTION, Go host (DEVLOG P10.2, D505-D508; SPEC 12.1). Its Rust twin is `host/rust/src/par.rs`: read that for the reasoning.
//
// WHAT RUNS IN PARALLEL is the host's work, never kin's: a request's fulfilment (a render, a decode, the OCR of a page, a crop), on a bounded pool of
// `workers` goroutines. Kin stays single-threaded and runs in the order it always did; the plan machine is the one it always was; the Model, the
// transcript and the accounting are the serial run's, byte for byte, at any number of workers. A round's requests run at once and their answers are
// taken in the order they were started; and while the loop is on page p the host runs DRY attempts (`pu-dry`: the part of an attempt that precedes its
// first write) of the pages ahead, has what they ask answered, and keeps the answers by the request's record, so that a unit which later asks the same
// request is answered by that job. A dry chain never writes, runs on the goroutine that runs the loop (a worker never touches kin or the document), and a
// panic or an error in it ends it and is dropped: the real unit meets the same condition at its own turn.
//
// THE LIMIT OVERRIDES of a conversion are process-wide here (D436): `port.WithLimits` holds a lock exclusively for a conversion that has overrides, so no
// other conversion runs while it does, and its workers, which run inside that scope and are all finished before it ends (`parPool.close`), read the same
// table. A worker never takes the lock itself (a read of a limit is an atomic load): taking it would wait on the conversion that holds it.
package emon

import (
	"github.com/3sln/emon/go/planrun"
	"github.com/3sln/emon/go/port"
	"sync"
	"sync/atomic"
)

// parExec is what a worker runs: one request to its answer. The port set of one conversion, callable from any goroutine (the same routing as
// `livePorts`).
type parExec struct {
	render *port.RenderFulfiller
	local  func(port.PlanRequest, port.PlanBitmaps) (port.PlanAnswer, error)
	ocr    bool
}

func (x *parExec) run(req port.PlanRequest, bm port.PlanBitmaps) (port.PlanAnswer, error) {
	if x.render != nil && planrun.RoutesToRenderer(req.Kind(), x.ocr) {
		return x.render.Fulfil(req)
	}
	return x.local(req, bm)
}

// parJob is one request on its way to an answer, shared by the loop's goroutine (which wants it), a dry chain (which asked first) and a worker.
type parJob struct {
	req      port.PlanRequest
	bm       port.PlanBitmaps
	taken    atomic.Bool
	mu       sync.Mutex
	done     bool
	panicked bool
	ans      port.PlanAnswer
	err      error
}

func (j *parJob) isDone() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.done
}

func (j *parJob) run(x *parExec) {
	defer func() {
		if r := recover(); r != nil {
			j.mu.Lock()
			j.panicked, j.done = true, true
			j.mu.Unlock()
		}
	}()
	a, err := x.run(j.req, j.bm)
	j.mu.Lock()
	j.ans, j.err, j.done = a, err, true
	j.mu.Unlock()
}

// parScout is a dry chain: one page ahead of the loop, asked round by round in a machine of its own (its accounting is not the run's).
type parScout struct {
	m *planrun.Machine
	// every request this chain has asked, by its record
	jobs map[string]*parJob
	// the round in flight, in id order
	waiting []*parJob
	done    bool
}

// parPool is the pool and the dry chains.
type parPool struct {
	exec  *parExec
	mu    sync.Mutex
	cv    *sync.Cond
	urgent, later []*parJob
	events int
	stop   bool
	wg     sync.WaitGroup
	// the workers wanted, and how many are started
	max, started int
	pcfg         port.PlanConfig
	ahead  int
	scouts map[int]*parScout
	// the page unit the loop is on (-1 outside the page units), how many there are, and a dry attempt of one of them: set by the engine's loop
	cur, pages int
	dry        func(page int, answers, rq *port.Cells, bms *port.BitmapSet, payload *port.Buf) int32
}

func newParPool(x *parExec, workers, ahead int, pcfg port.PlanConfig) *parPool {
	p := &parPool{exec: x, pcfg: pcfg, ahead: ahead, scouts: map[int]*parScout{}, cur: -1, max: workers}
	p.cv = sync.NewCond(&p.mu)
	return p
}

// grow starts one more worker, while there are fewer than `max`: the pool's goroutines are started as jobs arrive, one a job, so a document that asks nothing
// of any port (a born-digital one under `ocr: auto`) starts none. Called by the loop's goroutine only.
func (p *parPool) grow() {
	if p.started < p.max {
		p.started++
		p.wg.Add(1)
		go p.worker()
	}
}

func (p *parPool) worker() {
	defer p.wg.Done()
	for {
		p.mu.Lock()
		var job *parJob
		for job == nil {
			if p.stop {
				p.mu.Unlock()
				return
			}
			if len(p.urgent) > 0 {
				job, p.urgent = p.urgent[0], p.urgent[1:]
			} else if len(p.later) > 0 {
				job, p.later = p.later[0], p.later[1:]
			} else {
				p.cv.Wait()
			}
		}
		p.mu.Unlock()
		// a job the loop asked for after a dry chain queued it is in the queue twice: the first worker to reach it runs it
		if !job.taken.CompareAndSwap(false, true) {
			continue
		}
		job.run(p.exec)
		p.mu.Lock()
		p.events++
		p.mu.Unlock()
		p.cv.Broadcast()
	}
}

// close stops the workers once they have finished the job each is on: no goroutine of the pool outlives the conversion (and so its limit scope).
func (p *parPool) close() {
	p.mu.Lock()
	p.stop = true
	p.mu.Unlock()
	p.cv.Broadcast()
	p.wg.Wait()
}

func recKey(rec []int32) string {
	b := make([]byte, 0, len(rec)*4)
	for _, v := range rec {
		b = append(b, byte(v), byte(v>>8), byte(v>>16), byte(v>>24))
	}
	return string(b)
}

// snapshot copies a round's bitmaps: the machine adds to its own map as answers commit, and a worker reads this one meanwhile.
func snapshot(bm port.PlanBitmaps) port.PlanBitmaps {
	s := make(port.PlanBitmaps, len(bm))
	for k, v := range bm {
		s[k] = v
	}
	return s
}

func (p *parPool) submit(req port.PlanRequest, bm port.PlanBitmaps, urgent bool) *parJob {
	p.grow()
	job := &parJob{req: req, bm: bm}
	p.mu.Lock()
	if urgent {
		p.urgent = append(p.urgent, job)
	} else {
		p.later = append(p.later, job)
	}
	p.mu.Unlock()
	p.cv.Broadcast()
	return job
}

// want is the job that answers req: the one a dry chain of the page the loop is on already asked (moved up the queue if it has not started), or a new one.
func (p *parPool) want(req port.PlanRequest, bm port.PlanBitmaps) *parJob {
	if p.cur >= 0 {
		if s := p.scouts[p.cur]; s != nil {
			if job := s.jobs[recKey(req.Rec)]; job != nil {
				if !job.isDone() && !job.taken.Load() {
					p.mu.Lock()
					p.urgent = append(p.urgent, job)
					p.mu.Unlock()
					p.cv.Broadcast()
				}
				return job
			}
		}
	}
	return p.submit(req, snapshot(bm), true)
}

// pump moves every dry chain on as far as the answers in hand allow, and starts those for the pages ahead of the loop.
func (p *parPool) pump() {
	if p.cur < 0 || p.dry == nil {
		p.scouts = map[int]*parScout{}
		return
	}
	for page := range p.scouts {
		if page < p.cur {
			delete(p.scouts, page)
		}
	}
	last := min(p.cur+p.ahead, p.pages-1)
	for page := p.cur + 1; page <= last; page++ {
		if p.scouts[page] == nil {
			p.scouts[page] = &parScout{m: planrun.New(p.pcfg), jobs: map[string]*parJob{}}
		}
	}
	// the nearest page first: its jobs are the ones the loop will want soonest
	for page := p.cur; page <= last; page++ {
		if s := p.scouts[page]; s != nil {
			p.advance(page, s)
		}
	}
}

// advance is one dry chain, as far as it goes without waiting.
func (p *parPool) advance(page int, s *parScout) {
	for !s.done {
		if len(s.waiting) > 0 {
			for _, j := range s.waiting {
				if !j.isDone() {
					return
				}
			}
			ok := true
			for _, j := range s.waiting {
				j.mu.Lock()
				if j.panicked || j.err != nil {
					ok = false
				} else if s.m.Offer(j.ans) != nil {
					ok = false
				}
				j.mu.Unlock()
			}
			if _, err := s.m.Commit(); !ok || err != nil {
				s.done = true
				return
			}
			s.waiting = nil
		}
		next, err := s.m.Attempt(func(answers, rq *port.Cells, bms *port.BitmapSet, payload *port.Buf) int32 {
			return p.dry(page, answers, rq, bms, payload)
		}, port.PlanNoMisses{})
		if err != nil || next != planrun.Requests {
			s.done = true
			return
		}
		all := snapshot(s.m.Bitmaps())
		for _, r := range s.m.Requests() {
			key := recKey(r.Rec)
			job := s.jobs[key]
			if job == nil {
				job = p.submit(r, all, false)
				s.jobs[key] = job
			}
			s.waiting = append(s.waiting, job)
		}
	}
}

// wait is the answer of job, once it has run: the loop's goroutine keeps the dry chains moving while it waits.
func (p *parPool) wait(job *parJob) (port.PlanAnswer, error) {
	for {
		p.pump()
		job.mu.Lock()
		if job.done {
			a, err, panicked := job.ans, job.err, job.panicked
			job.mu.Unlock()
			if panicked {
				// a panic is the loop's own, in its own place: run the request here
				return p.exec.run(job.req, job.bm)
			}
			return a, err
		}
		job.mu.Unlock()
		p.mu.Lock()
		if p.events == 0 && !job.isDone() {
			p.cv.Wait()
		}
		p.events = 0
		p.mu.Unlock()
	}
}

// parPorts is the round's fulfiller: `Machine.Fulfil` starts every request of the round and then takes every completion in order.
type parPorts struct {
	pool    *parPool
	started []*parJob
}

func (f *parPorts) Start(req port.PlanRequest, bm port.PlanBitmaps) error {
	f.started = append(f.started, f.pool.want(req, bm))
	return nil
}

func (f *parPorts) Next() (port.PlanAnswer, error) {
	if len(f.started) == 0 {
		return port.PlanAnswer{}, planrun.FulfilFailed("next without a started request")
	}
	job := f.started[0]
	f.started = f.started[1:]
	return f.pool.wait(job)
}
