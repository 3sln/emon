;; Resolve the memory cycle without a JavaScript callback on dictionary reads.
(module
  (type $word (func (param i32 i32) (result i32)))
  (import "link" "calls" (table $calls 1 funcref))
  (func (export "valid_word") (type $word) (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.const 0
    call_indirect $calls (type $word)))
