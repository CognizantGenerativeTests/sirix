xquery version "1.0";
let $auction := . return
for $i in $auction/site//item
where contains(string(exactly-one($i/description)), "gold")
return $i/name/text()

