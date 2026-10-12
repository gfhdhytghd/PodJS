export function referenceQuote(s:string):string {
    const pieces:string[]=['"'];let start=0;
    const escapes:Record<string,string>={'"':'\\"','\\':'\\\\','/':'\\/','\b':'\\b','\f':'\\f','\n':'\\n','\r':'\\r','\t':'\\t'};
    for(let i=0;i<s.length;i++){
      const c=s[i]!,escaped=escapes[c]??(c.charCodeAt(0)<32?`\\u00${c.charCodeAt(0).toString(16).padStart(2,"0")}`:undefined);
      if(escaped!==undefined){pieces.push(s.slice(start,i),escaped);start=i+1;}
    }
    pieces.push(s.slice(start),'"');return pieces.join("");
}

