const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
    constructor() { this.dataset = {}; this.children = []; this.events = {}; this.value = ''; }
    setAttribute() {} focus() {} select() {}
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    appendChild(child) { this.children.push(child); }
    addEventListener(name, listener) { this.events[name] = listener; }
    set innerHTML(value) { this.children = [new Element()]; }
    async click() { await this.events.click?.({stopPropagation() {}}); }
}
function setup(fail = false) {
    const source = fs.readFileSync('src/main/resources/static/js/common.js','utf8');
    const start = source.indexOf('function editTitleFilterCell(');
    const fn = source.slice(start, source.indexOf('\n/* ===',start));
    const writes = [];
    const context = vm.createContext({document:{createElement:()=>new Element()},
        query:(_,parent)=>parent.children[0],escapeHtml:x=>x,clearError(){},showError(){},errorMessage:String,showToast(){},titleFilterButton:x=>x,
        apiPut:async (_,body)=>{if(fail) throw Error('保存失敗');writes.push(body.titleKeywords);}});
    vm.runInContext(fn,context);
    return {edit:(td,value)=>context.editTitleFilterCell(td,value,async (next)=>{if(fail) throw Error("保存失敗");writes.push(next);}),writes};
}
test('正常系：保存後の再編集と取消は最新の保存値を使う',async()=>{
    const {edit,writes}=setup(); const td=new Element();
    edit(td,'ASMR');td.children[0].children[0].value='雑談';await td.children[0].children[1].click();
    edit(td,'ASMR');assert.equal(td.children[0].children[0].value,'雑談');
    await td.children[0].children[2].click();assert.equal(td.dataset.value,'雑談');
    assert.deepEqual(writes,['雑談']);
});
test('異常系：保存失敗では前の保存値に戻り、二重編集を開始しない',async()=>{
    const {edit}=setup(true); const td=new Element();
    edit(td,'ASMR');const input=td.children[0].children[0];input.value='雑談';
    edit(td,'別の値');assert.equal(td.children[0].children[0],input);
    await td.children[0].children[1].click();assert.equal(td.dataset.value,'ASMR');
});
