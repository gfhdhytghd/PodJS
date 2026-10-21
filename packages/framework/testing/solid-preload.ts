// Test/runtime imports of framework TSX need the same universal Solid transform
// as the production compiler. Application component tests own their transforms.
import {plugin} from 'bun';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../../../vendor/pocketjs/package.json',import.meta.url));
plugin({name:'podjs-framework-solid',setup(builder){builder.onLoad({filter:/\/packages\/framework\/src\/[^]+\.tsx$/},async({path})=>({loader:'js',contents:require('@babel/core').transformSync(await Bun.file(path).text(),{filename:path,babelrc:false,configFile:false,presets:[[require('babel-preset-solid'),{generate:'universal',moduleName:'solid-js'}],[require('@babel/preset-typescript'),{isTSX:true,allExtensions:true}]]}).code.replace(/import \{ memo as /g,'import { createMemo as ')}));}});
