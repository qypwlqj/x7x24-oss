package com.x7x24.gov;

import com.x7x24.engine.ProjectCfg;
import java.util.*;

/** Conservative lexical checks, never evaluates generated source or reads environment values. */
final class CodeContentPolicy {
  private record Token(String text,boolean literal,int offset) {}
  record Finding(String code,String reason,int offset) {}
  static Finding check(ProjectCfg project,String path,String content) {
    var ts=tokens(content);boolean client=ts.stream().anyMatch(t->t.literal && t.text.equals("use client"));
    for(int i=0;i<ts.size();i++) {
      var t=ts.get(i);if(t.literal)continue;
      if(Set.of("execSync","execFile","execFileSync","spawn","spawnSync","printenv").contains(t.text))return new Finding("PROCESS_EXECUTION","内容包含进程执行或环境导出代码",t.offset);
      if(t.text.equals("os") && at(ts,i+1,".") && at(ts,i+2,"environ"))return new Finding("ENV_ENUMERATION","内容包含未声明的环境变量读取",t.offset);
      if((t.text.equals("require") || t.text.equals("import")) && at(ts,i+1,"(") && i+2<ts.size() && ts.get(i+2).literal && Set.of("http","https","child_process","node:child_process","node:http","node:https").contains(ts.get(i+2).text))return new Finding("EXTERNAL_EXECUTION","内容包含外联或进程执行模块",t.offset);
      if(t.text.equals("from") && i+1<ts.size() && ts.get(i+1).literal && ts.get(i+1).text.contains("child_process"))return new Finding("PROCESS_EXECUTION","内容包含进程执行模块",t.offset);
      if(t.text.equals("fetch") && at(ts,i+1,"(") && i+2<ts.size() && ts.get(i+2).literal && ts.get(i+2).text.matches("(?i)https?:.*"))return new Finding("EXTERNAL_REQUEST","内容包含未授权的外联请求",t.offset);
      if(!t.text.equals("process"))continue;
      int j=i+1;if(at(ts,j,"?"))j++;
      if(at(ts,j,".") && at(ts,j+1,"env"))j+=2;
      else if(at(ts,j,"[") && at(ts,j+1,"env") && at(ts,j+2,"]"))j+=3;
      else continue;
      String name=null;
      if(at(ts,j,".") && j+1<ts.size() && !ts.get(j+1).literal)name=ts.get(j+1).text;
      if(at(ts,j,"[") && j+2<ts.size() && ts.get(j+1).literal && at(ts,j+2,"]"))name=ts.get(j+1).text;
      if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))return new Finding("ENV_ENUMERATION","禁止整份环境读取或无法确定变量名的动态读取",t.offset);
      boolean ordinary=Set.of("NODE_ENV","PORT","HOSTNAME").contains(name) || name.startsWith("NEXT_PUBLIC_") && !name.matches("(?i).*(SECRET|TOKEN|PASSWORD|PRIVATE|CREDENTIAL|API_KEY).*" );
      if(ordinary)continue;
      boolean declared=project.allowedEnvNames.contains(name) && project.serverEnvPaths.stream().anyMatch(path::startsWith) && !client;
      if(!declared)return new Finding("ENV_NOT_DECLARED","环境变量 "+name+" 需要声明服务端配置入口",t.offset);
      // Do not approve a secret read in a file that can expose it; ambiguous usage stays blocked.
      if(ts.stream().anyMatch(x->!x.literal && Set.of("console","logger","fetch","send","json","stringify","write","log").contains(x.text)))return new Finding("ENV_DISCLOSURE","服务端敏感配置不能进入日志、响应或外联代码",t.offset);
    }
    return null;
  }
  private static boolean at(List<Token> ts,int i,String text) {return i<ts.size() && ts.get(i).text.equals(text);}
  private static List<Token> tokens(String s) {
    List<Token> out=new ArrayList<>();int i=0;
    while(i<s.length()) {
      char c=s.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
      if(c=='/' && i+1<s.length() && s.charAt(i+1)=='/'){i+=2;while(i<s.length() && s.charAt(i)!='\n')i++;continue;}
      if(c=='/' && i+1<s.length() && s.charAt(i+1)=='*'){int end=s.indexOf("*/",i+2);i=end<0?s.length():end+2;continue;}
      int start=i;
      if(c=='\'' || c=='"' || c=='`') {
        i++;var value=new StringBuilder();
        while(i<s.length()) {char q=s.charAt(i++);if(q=='\\' && i<s.length()){value.append(s.charAt(i++));continue;}if(q==c)break;value.append(q);}
        String text=value.toString();
        if(c=='`' && text.contains("${")) { // Scan interpolation conservatively, including nested expressions.
          for(var token:tokens(text))out.add(new Token(token.text,token.literal,start+1+token.offset));
        } else out.add(new Token(text,true,start));
        continue;
      }
      if(Character.isJavaIdentifierStart(c)){i++;while(i<s.length() && Character.isJavaIdentifierPart(s.charAt(i)))i++;out.add(new Token(s.substring(start,i),false,start));}
      else {out.add(new Token(String.valueOf(c),false,i));i++;}
    }
    return out;
  }
}
